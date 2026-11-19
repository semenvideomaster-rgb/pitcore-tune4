#pragma once
#include <juce_audio_processors/juce_audio_processors.h>
#include <juce_dsp/juce_dsp.h>

class N1081Processor : public juce::AudioProcessor
{
public:
    N1081Processor();
    void prepareToPlay (double, int) override;
    void releaseResources() override {}
    bool isBusesLayoutSupported (const BusesLayout&) const override;
    void processBlock (juce::AudioBuffer<float>&, juce::MidiBuffer&) override;
    juce::AudioProcessorEditor* createEditor() override;
    bool hasEditor() const override { return true; }
    const juce::String getName() const override { return "NEVE 1081"; }
    bool acceptsMidi() const override { return false; }
    bool producesMidi() const override { return false; }
    double getTailLengthSeconds() const override { return 0.0; }
    int getNumPrograms() override { return 1; }
    int getCurrentProgram() override { return 0; }
    void setCurrentProgram (int) override {}
    const juce::String getProgramName (int) override { return {}; }
    void changeProgramName (int, const juce::String&) override {}
    void getStateInformation (juce::MemoryBlock&) override;
    void setStateInformation (const void*, int) override;
    juce::AudioProcessorValueTreeState apvts;
private:
    static juce::AudioProcessorValueTreeState::ParameterLayout createLayout();
    float param (const char* id) const { return apvts.getRawParameterValue (id)->load(); }
    void updateFilters();
    double sr = 44100.0;
    // [channel][band]: 0 HPF, 1 low shelf, 2 low-mid, 3 high-mid, 4 high shelf
    juce::dsp::IIR::Filter<float> f[2][5];
    float last[7] { 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f };
    juce::SmoothedValue<float> inG, outG, warm;
    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (N1081Processor)
};
