cmake_minimum_required(VERSION 3.22)
project(N1081 VERSION 1.0.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(JUCE
    GIT_REPOSITORY https://github.com/juce-framework/JUCE.git
    GIT_TAG 8.0.15
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(JUCE)

juce_add_plugin(N1081
    COMPANY_NAME "PitCore"
    PLUGIN_MANUFACTURER_CODE Nv10
    PLUGIN_CODE N108
    FORMATS VST3
    PRODUCT_NAME "NEVE 1081"
    IS_SYNTH FALSE
    NEEDS_MIDI_INPUT FALSE
    NEEDS_MIDI_OUTPUT FALSE
    VST3_CATEGORIES Fx Nv10h Shift
    COPY_PLUGIN_AFTER_BUILD FALSE)

target_sources(N1081 PRIVATE Source/PluginProcessor.cpp Source/PluginProcessor.h )
target_compile_definitions(N1081 PUBLIC
    JUCE_WEB_BROWSER=0 JUCE_USE_CURL=0 JUCE_VST3_CAN_REPLACE_VST2=0)
target_link_libraries(N1081
    PRIVATE juce::juce_audio_utils juce::juce_dsp
    PUBLIC juce::juce_recommended_config_flags juce::juce_recommended_warning_flags)
