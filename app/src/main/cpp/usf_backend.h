#pragma once

#include <cstdint>
#include <string>

struct UsfTags {
  std::string title;
  std::string game;
  std::string artist;
  std::string year;
  std::string ripper;
  std::string comment;
};

bool usf_backend_open(const char *path, int sample_rate);
void usf_backend_close();
int usf_backend_render(int16_t *stereo, int frames);
bool usf_backend_seek(uint64_t sample);
uint64_t usf_backend_current_sample();
uint64_t usf_backend_total_samples();
const UsfTags &usf_backend_tags();
