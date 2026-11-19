#include "PluginEditor.h"

void NeveEditor::addKnob (const char* id, const juce::String& title, const char* suffix, int decimals)
{
    auto k = std::make_unique<Knob>();
    k->id = id; k->title = title; k->suffix = suffix; k->decimals = decimals;
    k->slider.setSliderStyle (juce::Slider::RotaryVerticalDrag);
    k->slider.setTextBoxStyle (juce::Slider::TextBoxBelow, false, 64, 16);
    k->slider.setRotaryParameters (juce::degreesToRadians (225.f), juce::degreesToRadians (495.f), true);
    k->slider.setTextValueSuffix (suffix);
    k->slider.setNumDecimalPlacesToDisplay (decimals);
    k->slider.setLookAndFeel (&laf);
    k->slider.setColour (juce::Slider::textBoxTextColourId, juce::Colour (0xff2dffb3));
    k->slider.setColour (juce::Slider::textBoxOutlineColourId, juce::Colours::transparentBlack);
    k->slider.setColour (juce::Slider::textBoxBackgroundColourId, juce::Colours::transparentBlack);
    k->att = std::make_unique<juce::AudioProcessorValueTreeState::SliderAttachment> (proc.apvts, id, k->slider);
    addAndMakeVisible (k->slider);
    knobs.push_back (std::move (k));
}

NeveEditor::NeveEditor (N1081Processor& p) : juce::AudioProcessorEditor (&p), proc (p)
{
    using S = juce::String;
    auto U = [] (const char* s) { return S::fromUTF8 (s); };
    addKnob ("in",   U ("Преамп"),  " дБ", 1);   // 0
    addKnob ("hpf",  U ("ФВЧ"),     " Гц", 0);   // 1
    addKnob ("warm", U ("Тепло"),   "",    2);   // 2
    addKnob ("lf",   U ("НЧ"),      " дБ", 1);   // 3
    addKnob ("lfF",  U ("Част"),    " Гц", 0);   // 4
    addKnob ("lmf",  U ("Н-Ср"),    " дБ", 1);   // 5
    addKnob ("lmfF", U ("Част"),    " Гц", 0);   // 6
    addKnob ("hmf",  U ("В-Ср"),    " дБ", 1);   // 7
    addKnob ("hmfF", U ("Част"),    " Гц", 0);   // 8
    addKnob ("hf",   U ("Air"),     " дБ", 1);   // 9
    addKnob ("hfF",  U ("Част"),    " Гц", 0);   // 10
    addKnob ("out",  U ("Выход"),   " дБ", 1);   // 11
    sections = { { U ("ПРЕАМП"), { 0, 1 }, {} }, { U ("ТЕПЛО"), { 2 }, {} }, { U ("НЧ"), { 3, 4 }, {} },
                 { U ("НИЖН. СРЕДНИЕ"), { 5, 6 }, {} }, { U ("ВЕРХН. СРЕДНИЕ"), { 7, 8 }, {} },
                 { U ("ВЫСОКИЕ"), { 9, 10 }, {} }, { U ("ВЫХОД"), { 11 }, {} } };
    setSize (980, 380);
    startTimerHz (20);
}

NeveEditor::~NeveEditor()
{
    for (auto& k : knobs) k->slider.setLookAndFeel (nullptr);
}

float NeveEditor::curveDb (double f) const
{
    auto v = [this] (const char* id) { return proc.apvts.getRawParameterValue (id)->load(); };
    const double sr = 48000.0;
    using C = juce::dsp::IIR::Coefficients<float>;
    auto g = [] (float db) { return juce::Decibels::decibelsToGain (db); };
    double m = C::makeHighPass (sr, v ("hpf"), 0.707f)->getMagnitudeForFrequency (f, sr);
    m *= C::makeLowShelf (sr, v ("lfF"), 0.7f, g (v ("lf")))->getMagnitudeForFrequency (f, sr);
    m *= C::makePeakFilter (sr, v ("lmfF"), 0.8f, g (v ("lmf")))->getMagnitudeForFrequency (f, sr);
    m *= C::makePeakFilter (sr, v ("hmfF"), 0.8f, g (v ("hmf")))->getMagnitudeForFrequency (f, sr);
    m *= C::makeHighShelf (sr, v ("hfF"), 0.7f, g (v ("hf")))->getMagnitudeForFrequency (f, sr);
    return (float) juce::Decibels::gainToDecibels (m, -60.0);
}

void NeveEditor::paint (juce::Graphics& g)
{
    g.setGradientFill (juce::ColourGradient (juce::Colour (0xff1b2128), 0.f, 0.f, juce::Colour (0xff0b0d10), 0.f, (float) getHeight(), false));
    g.fillAll();
    const auto green = juce::Colour (0xff2dffb3);
    // заголовок
    g.setColour (green.withAlpha (0.25f)); g.setFont (juce::Font (juce::FontOptions (26.f, juce::Font::bold)));
    g.drawText ("N E V E   1 0 8 1", getLocalBounds().removeFromTop (40).translated (0, 2), juce::Justification::centred);
    g.setColour (green); g.drawText ("N E V E   1 0 8 1", getLocalBounds().removeFromTop (40), juce::Justification::centred);
    g.setColour (juce::Colour (0xff7d8b99)); g.setFont (juce::Font (juce::FontOptions (11.f)));
    g.drawText (juce::String::fromUTF8 ("ЭКВАЛАЙЗЕР И ПРЕАМП"), getLocalBounds().removeFromTop (58).removeFromBottom (16), juce::Justification::centred);

    // кривая эквалайзера
    auto ca = curveArea.toFloat();
    g.setColour (juce::Colour (0xff0e1216)); g.fillRoundedRectangle (ca, 8.f);
    g.setColour (juce::Colour (0xff2a343e)); g.drawRoundedRectangle (ca, 8.f, 1.f);
    g.setFont (juce::Font (juce::FontOptions (9.f)));
    for (int d : { -12, -6, 0, 6, 12 })
    {
        const float y = ca.getCentreY() - (float) d / 15.f * ca.getHeight() * 0.5f * 0.9f;
        g.setColour (juce::Colour (0xff1f2a33)); g.drawHorizontalLine ((int) y, ca.getX(), ca.getRight());
        g.setColour (juce::Colour (0xff5d6c7a)); g.drawText (juce::String (d), (int) ca.getX() + 4, (int) y - 10, 30, 10, juce::Justification::left);
    }
    for (double f : { 100.0, 1000.0, 10000.0 })
    {
        const float x = ca.getX() + (float) (std::log (f / 20.0) / std::log (1000.0)) * ca.getWidth();
        g.setColour (juce::Colour (0xff1f2a33)); g.drawVerticalLine ((int) x, ca.getY(), ca.getBottom());
        g.setColour (juce::Colour (0xff5d6c7a)); g.drawText (f >= 1000 ? juce::String ((int) (f / 1000)) + "k" : juce::String ((int) f), (int) x + 3, (int) ca.getBottom() - 12, 30, 10, juce::Justification::left);
    }
    juce::Path p;
    for (int i = 0; i <= (int) ca.getWidth(); i += 2)
    {
        const double f = 20.0 * std::pow (1000.0, (double) i / ca.getWidth());
        const float d = juce::jlimit (-15.f, 15.f, curveDb (f));
        const float y = ca.getCentreY() - d / 15.f * ca.getHeight() * 0.5f * 0.9f;
        if (i == 0) p.startNewSubPath (ca.getX(), y); else p.lineTo (ca.getX() + (float) i, y);
    }
    g.setColour (green.withAlpha (0.2f)); g.strokePath (p, juce::PathStrokeType (7.f));
    g.setColour (green); g.strokePath (p, juce::PathStrokeType (2.5f));

    // секции и подписи ручек
    for (auto& s : sections)
    {
        auto a = s.area.toFloat();
        g.setColour (juce::Colour (0xff0e1216)); g.fillRoundedRectangle (a, 10.f);
        g.setColour (juce::Colour (0xff2a343e)); g.drawRoundedRectangle (a, 10.f, 1.f);
        g.setColour (juce::Colour (0xff9fb2c4)); g.setFont (juce::Font (juce::FontOptions (10.f, juce::Font::bold)));
        g.drawText (s.title, s.area.withHeight (22), juce::Justification::centred);
        for (int idx : s.knobs)
        {
            auto sb = knobs[(size_t) idx]->slider.getBounds();
            g.setColour (juce::Colour (0xff8da0b3)); g.setFont (juce::Font (juce::FontOptions (10.f)));
            g.drawText (knobs[(size_t) idx]->title, sb.getX() - 4, sb.getBottom(), sb.getWidth() + 8, 14, juce::Justification::centred);
        }
    }
}

void NeveEditor::resized()
{
    auto r = getLocalBounds().reduced (16, 0);
    r.removeFromTop (62);
    curveArea = r.removeFromTop (120);
    r.removeFromTop (14);
    const int cell = 66, gap = 6, pad = 8;
    int total = 0; for (auto& s : sections) total += (int) s.knobs.size() * cell + pad * 2;
    total += gap * ((int) sections.size() - 1);
    int x = r.getX() + (r.getWidth() - total) / 2;
    const int y = r.getY(), h = 150;
    for (auto& s : sections)
    {
        const int w = (int) s.knobs.size() * cell + pad * 2;
        s.area = { x, y, w, h };
        int kx = x + pad;
        for (int idx : s.knobs) { knobs[(size_t) idx]->slider.setBounds (kx, y + 24, cell, cell + 16 + 4); kx += cell; }
        x += w + gap;
    }
}
