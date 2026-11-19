#pragma once
#include "PluginProcessor.h"

// Неоновые ручки в стиле веб-версии NEVE 1081
class NeonLookAndFeel : public juce::LookAndFeel_V4
{
public:
    void drawRotarySlider (juce::Graphics& g, int x, int y, int w, int h, float pos,
                           float startAngle, float endAngle, juce::Slider& s) override
    {
        auto b = juce::Rectangle<float> ((float) x, (float) y, (float) w, (float) h).reduced (4.f);
        const float r = std::min (b.getWidth(), b.getHeight()) * 0.5f;
        const auto c = b.getCentre();
        const float arcR = r - 3.f, ang = startAngle + pos * (endAngle - startAngle);
        const auto green = juce::Colour (0xff2dffb3);

        juce::Path track; track.addCentredArc (c.x, c.y, arcR, arcR, 0.f, startAngle, endAngle, true);
        g.setColour (juce::Colour (0xff243039));
        g.strokePath (track, juce::PathStrokeType (5.f, juce::PathStrokeType::curved, juce::PathStrokeType::rounded));

        juce::Path val; val.addCentredArc (c.x, c.y, arcR, arcR, 0.f, startAngle, ang, true);
        for (int i = 3; i >= 1; --i)           // свечение
        {
            g.setColour (green.withAlpha (0.10f * (float) (4 - i)));
            g.strokePath (val, juce::PathStrokeType (5.f + 3.f * (float) i, juce::PathStrokeType::curved, juce::PathStrokeType::rounded));
        }
        g.setColour (green);
        g.strokePath (val, juce::PathStrokeType (5.f, juce::PathStrokeType::curved, juce::PathStrokeType::rounded));

        const float ir = r * 0.5f;
        g.setColour (juce::Colour (0xff10161b)); g.fillEllipse (c.x - ir, c.y - ir, ir * 2.f, ir * 2.f);
        g.setColour (juce::Colour (0xff33475a)); g.drawEllipse (c.x - ir, c.y - ir, ir * 2.f, ir * 2.f, 1.f);
        g.setColour (juce::Colours::white);
        g.drawLine (c.x, c.y, c.x + std::sin (ang) * ir, c.y - std::cos (ang) * ir, 2.f);
        juce::ignoreUnused (s);
    }
    juce::Font getLabelFont (juce::Label&) override { return juce::Font (juce::FontOptions (11.f)); }
};

class NeveEditor : public juce::AudioProcessorEditor, private juce::Timer
{
public:
    explicit NeveEditor (N1081Processor&);
    ~NeveEditor() override;
    void paint (juce::Graphics&) override;
    void resized() override;
private:
    void timerCallback() override { repaint (curveArea); }
    float curveDb (double f) const;

    struct Knob
    {
        juce::String id, title, suffix; int decimals;
        juce::Slider slider;
        std::unique_ptr<juce::AudioProcessorValueTreeState::SliderAttachment> att;
    };
    struct Section { juce::String title; std::vector<int> knobs; juce::Rectangle<int> area; };

    N1081Processor& proc;
    NeonLookAndFeel laf;
    std::vector<std::unique_ptr<Knob>> knobs;
    std::vector<Section> sections;
    juce::Rectangle<int> curveArea;
    void addKnob (const char* id, const juce::String& title, const char* suffix, int decimals);

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (NeveEditor)
};
