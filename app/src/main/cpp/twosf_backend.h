#pragma once

#include <cstdint>
#include <string>

struct TwoSfTags {
  std::string title;
  std::string game;
  std::string artist;
  std::string year;
  std::string ripper;
  std::string comment;
};

bool twosf_open(const char *path, int sample_rate);
void twosf_close();
int twosf_render(int16_t *stereo, int frames);
bool twosf_seek(uint64_t sample);
uint64_t twosf_current_sample();
uint64_t twosf_total_samples();
const TwoSfTags &twosf_tags();
