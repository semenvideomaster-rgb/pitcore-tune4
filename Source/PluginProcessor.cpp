#include "PluginProcessor.h"

N1081Processor::N1081Processor()
    : AudioProcessor (BusesProperties().withInput ("Input", juce::AudioChannelSet::stereo(), true)
                                       .withOutput ("Output", juce::AudioChannelSet::stereo(), true)),
      apvts (*this, nullptr, "STATE", createLayout()) {}

juce::AudioProcessorValueTreeState::ParameterLayout N1081Processor::createLayout()
{
    std::vector<std::unique_ptr<juce::RangedAudioParameter>> p;
    auto F = [&] (const char* id, const char* name, float lo, float hi, float def, float step = 0.f)
    { p.push_back (std::make_unique<juce::AudioParameterFloat> (juce::ParameterID { id, 1 }, name, juce::NormalisableRange<float> (lo, hi, step), def)); };
    F ("in",    "Преамп (вход), дБ", -12.f, 24.f, 0.f, 0.5f);
    F ("hpf",   "ФВЧ, Гц",           20.f, 300.f, 20.f, 1.f);
    F ("warm",  "Тепло",             0.f, 1.f, 0.2f);
    F ("lf",    "НЧ 60-220 Гц, дБ", -15.f, 15.f, 0.f, 0.5f);
    F ("lfF",   "НЧ частота, Гц",    35.f, 220.f, 100.f, 1.f);
    F ("lmf",   "Н-Ср 0.36-7 кГц, дБ", -15.f, 15.f, 0.f, 0.5f);
    F ("lmfF",  "Н-Ср частота, Гц",  360.f, 7200.f, 700.f, 10.f);
    F ("hmf",   "В-Ср, дБ",         -15.f, 15.f, 0.f, 0.5f);
    F ("hmfF",  "В-Ср частота, Гц",  1500.f, 7200.f, 3200.f, 10.f);
    F ("hf",    "ВЧ (air), дБ",     -15.f, 15.f, 0.f, 0.5f);
    F ("hfF",   "ВЧ частота, Гц",    5000.f, 16000.f, 10000.f, 100.f);
    F ("out",   "Выход, дБ",        -24.f, 12.f, 0.f, 0.5f);
    return { p.begin(), p.end() };
}

bool N1081Processor::isBusesLayoutSupported (const BusesLayout& l) const
{
    const auto o = l.getMainOutputChannelSet();
    return (o == juce::AudioChannelSet::mono() || o == juce::AudioChannelSet::stereo()) && o == l.getMainInputChannelSet();
}

void N1081Processor::prepareToPlay (double rate, int)
{
    sr = rate;
    for (auto& c : f) for (auto& b : c) b.reset();
    for (auto& v : last) v = 1e9f;
    inG.reset (sr, 0.03); inG.setCurrentAndTargetValue (juce::Decibels::decibelsToGain (param ("in")));
    outG.reset (sr, 0.03); outG.setCurrentAndTargetValue (juce::Decibels::decibelsToGain (param ("out")));
    warm.reset (sr, 0.05); warm.setCurrentAndTargetValue (param ("warm"));
}

void N1081Processor::updateFilters()
{
    const float v[7] { param ("hpf"), param ("lf"), param ("lfF"), param ("lmf") + 1000.f * param ("lmfF"),
                       param ("hmf") + 1000.f * param ("hmfF"), param ("hf") + 1000.f * param ("hfF"), 0.f };
    bool ch = false; for (int i = 0; i < 6; ++i) if (v[i] != last[i]) { ch = true; last[i] = v[i]; }
    if (! ch) return;
    using C = juce::dsp::IIR::Coefficients<float>;
    auto g = [] (float db) { return juce::Decibels::decibelsToGain (db); };
    auto c0 = C::makeHighPass (sr, param ("hpf"), 0.707f);
    auto c1 = C::makeLowShelf (sr, param ("lfF"), 0.7f, g (param ("lf")));
    auto c2 = C::makePeakFilter (sr, param ("lmfF"), 0.8f, g (param ("lmf")));
    auto c3 = C::makePeakFilter (sr, param ("hmfF"), 0.8f, g (param ("hmf")));
    auto c4 = C::makeHighShelf (sr, std::min (param ("hfF"), (float) (sr * 0.45)), 0.7f, g (param ("hf")));
    for (auto& c : f) { c[0].coefficients = c0; c[1].coefficients = c1; c[2].coefficients = c2; c[3].coefficients = c3; c[4].coefficients = c4; }
}

void N1081Processor::processBlock (juce::AudioBuffer<float>& buf, juce::MidiBuffer&)
{
    juce::ScopedNoDenormals nd;
    updateFilters();
    inG.setTargetValue (juce::Decibels::decibelsToGain (param ("in")));
    outG.setTargetValue (juce::Decibels::decibelsToGain (param ("out")));
    warm.setTargetValue (param ("warm"));
    const int n = buf.getNumSamples(), nch = std::min (buf.getNumChannels(), 2);
    // параметры сглаживаются одним проходом по каждому отсчёту; значения берём для всех каналов поровну
    for (int i = 0; i < n; ++i)
    {
        const float gi = inG.getNextValue(), go = outG.getNextValue(), w = warm.getNextValue();
        for (int c = 0; c < nch; ++c)
        {
            float x = buf.getReadPointer (c)[i] * gi;
            // параллельное «тепло»: чистый сигнал + тёплая гармоника tanh
            const float sat = std::tanh (x * 1.8f) / 1.8f;
            x = x * (1.f - 0.5f * w) + sat * (0.5f * w) * 1.4f;
            for (int b = 0; b < 5; ++b) x = f[c][b].processSample (x);
            x = std::tanh (x * 0.9f) / 0.9f;           // мягкий лимитер
            buf.getWritePointer (c)[i] = x * go;
        }
    }
}

void N1081Processor::getStateInformation (juce::MemoryBlock& d)
{ if (auto x = apvts.copyState().createXml()) copyXmlToBinary (*x, d); }
void N1081Processor::setStateInformation (const void* data, int size)
{ if (auto x = getXmlFromBinary (data, size)) if (x->hasTagName (apvts.state.getType())) apvts.replaceState (juce::ValueTree::fromXml (*x)); }
juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter() { return new N1081Processor(); }
