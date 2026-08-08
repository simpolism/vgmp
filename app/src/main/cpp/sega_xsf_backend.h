#pragma once

#include <cstdint>
#include <string>

struct SegaXsfTags {
  std::string title;
  std::string game;
  std::string artist;
  std::string year;
  std::string ripper;
  std::string comment;
};

bool sega_xsf_open(const char *path, int sample_rate);
void sega_xsf_close();
int sega_xsf_render(int16_t *stereo, int frames);
bool sega_xsf_seek(uint64_t sample);
uint64_t sega_xsf_current_sample();
uint64_t sega_xsf_total_samples();
bool sega_xsf_is_dreamcast();
const SegaXsfTags &sega_xsf_tags();
