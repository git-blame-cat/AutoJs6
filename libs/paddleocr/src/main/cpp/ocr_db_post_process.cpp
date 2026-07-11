// Copyright (c) 2020 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "ocr_clipper.hpp"
#include "opencv2/core.hpp"
#include "opencv2/imgcodecs.hpp"
#include "opencv2/imgproc.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <vector>

using FloatBox = std::array<cv::Point2f, 4>;

static bool getcontourarea(const FloatBox &box, float unclip_ratio,
                           float &distance) {
  int pts_num = 4;
  float area = 0.0f;
  float dist = 0.0f;
  for (int i = 0; i < pts_num; i++) {
    const cv::Point2f &current = box[i];
    const cv::Point2f &next = box[(i + 1) % pts_num];
    area += current.x * next.y - current.y * next.x;
    dist += static_cast<float>(cv::norm(current - next));
  }
  area = std::fabs(area / 2.0f);

  if (dist <= 0.0f) {
    distance = 0.0f;
    return false;
  }
  distance = area * unclip_ratio / dist;
  return std::isfinite(distance) && distance > 0.0f;
}

static cv::RotatedRect unclip(const FloatBox &box) {
  float unclip_ratio = 2.0;
  float distance = 1.0;

  if (!getcontourarea(box, unclip_ratio, distance)) {
    return {};
  }

  ClipperLib::ClipperOffset offset;
  ClipperLib::Path p;
  p << ClipperLib::IntPoint(int(box[0].x), int(box[0].y))
    << ClipperLib::IntPoint(int(box[1].x), int(box[1].y))
    << ClipperLib::IntPoint(int(box[2].x), int(box[2].y))
    << ClipperLib::IntPoint(int(box[3].x), int(box[3].y));
  offset.AddPath(p, ClipperLib::jtRound, ClipperLib::etClosedPolygon);

  ClipperLib::Paths soln;
  offset.Execute(soln, distance);
  std::vector<cv::Point2f> points;

  for (const auto &path : soln) {
    for (const auto &point : path) {
      points.emplace_back(point.X, point.Y);
    }
  }
  if (points.empty()) {
    return {};
  }
  return cv::minAreaRect(points);
}

static void quickSort_vector(std::vector<std::vector<int>> &box, int l, int r,
                             int axis) {
  if (l < r) {
    int i = l, j = r;
    int x = box[l][axis];
    std::vector<int> xp(box[l]);
    while (i < j) {
      while (i < j && box[j][axis] >= x) {
        j--;
      }
      if (i < j) {
        std::swap(box[i++], box[j]);
      }
      while (i < j && box[i][axis] < x) {
        i++;
      }
      if (i < j) {
        std::swap(box[j--], box[i]);
      }
    }
    box[i] = xp;
    quickSort_vector(box, l, i - 1, axis);
    quickSort_vector(box, i + 1, r, axis);
  }
}

static std::vector<std::vector<int>>
order_points_clockwise(std::vector<std::vector<int>> pts) {
  std::vector<std::vector<int>> box = pts;
  quickSort_vector(box, 0, int(box.size() - 1), 0);
  std::vector<std::vector<int>> leftmost = {box[0], box[1]};
  std::vector<std::vector<int>> rightmost = {box[2], box[3]};

  if (leftmost[0][1] > leftmost[1][1]) {
    std::swap(leftmost[0], leftmost[1]);
  }

  if (rightmost[0][1] > rightmost[1][1]) {
    std::swap(rightmost[0], rightmost[1]);
  }

  std::vector<std::vector<int>> rect = {leftmost[0], rightmost[0], rightmost[1],
                                        leftmost[1]};
  return rect;
}

static FloatBox get_mini_boxes(const cv::RotatedRect &box, float &ssid) {
  ssid = box.size.width >= box.size.height ? box.size.height : box.size.width;

  FloatBox array;
  box.points(array.data());
  std::sort(array.begin(), array.end(), [](const cv::Point2f &left,
                                           const cv::Point2f &right) {
    return left.x < right.x;
  });

  cv::Point2f idx1, idx2, idx3, idx4;
  if (array[3].y <= array[2].y) {
    idx2 = array[3];
    idx3 = array[2];
  } else {
    idx2 = array[2];
    idx3 = array[3];
  }
  if (array[1].y <= array[0].y) {
    idx1 = array[1];
    idx4 = array[0];
  } else {
    idx1 = array[0];
    idx4 = array[1];
  }

  array[0] = idx1;
  array[1] = idx2;
  array[2] = idx3;
  array[3] = idx4;

  return array;
}

template <class T> T clamp(T x, T min, T max) {
  if (x > max) {
    return max;
  }
  if (x < min) {
    return min;
  }
  return x;
}

static float clampf(float x, float min, float max) {
  if (x > max)
    return max;
  if (x < min)
    return min;
  return x;
}

float box_score_fast(const FloatBox &array, const cv::Mat &pred) {
  int width = pred.cols;
  int height = pred.rows;

  float box_x[4] = {array[0].x, array[1].x, array[2].x, array[3].x};
  float box_y[4] = {array[0].y, array[1].y, array[2].y, array[3].y};

  int xmin = clamp(int(std::floorf(*(std::min_element(box_x, box_x + 4)))), 0,
                   width - 1);
  int xmax = clamp(int(std::ceilf(*(std::max_element(box_x, box_x + 4)))), 0,
                   width - 1);
  int ymin = clamp(int(std::floorf(*(std::min_element(box_y, box_y + 4)))), 0,
                   height - 1);
  int ymax = clamp(int(std::ceilf(*(std::max_element(box_y, box_y + 4)))), 0,
                   height - 1);

  cv::Mat mask;
  mask = cv::Mat::zeros(ymax - ymin + 1, xmax - xmin + 1, CV_8UC1);

  cv::Point root_point[4];
  root_point[0] = cv::Point(int(array[0].x) - xmin, int(array[0].y) - ymin);
  root_point[1] = cv::Point(int(array[1].x) - xmin, int(array[1].y) - ymin);
  root_point[2] = cv::Point(int(array[2].x) - xmin, int(array[2].y) - ymin);
  root_point[3] = cv::Point(int(array[3].x) - xmin, int(array[3].y) - ymin);
  const cv::Point *ppt[1] = {root_point};
  int npt[] = {4};
  cv::fillPoly(mask, ppt, npt, 1, cv::Scalar(1));

  cv::Mat croppedImg;
  pred(cv::Rect(xmin, ymin, xmax - xmin + 1, ymax - ymin + 1))
      .copyTo(croppedImg);

  auto score = cv::mean(croppedImg, mask)[0];
  return score;
}

std::vector<std::vector<std::vector<int>>>
boxes_from_bitmap(const cv::Mat &pred, const cv::Mat &bitmap) {
  const int min_size = 3;
  const int max_candidates = 1000;
  const float box_thresh = 0.5;

  int width = bitmap.cols;
  int height = bitmap.rows;

  std::vector<std::vector<cv::Point>> contours;
  std::vector<cv::Vec4i> hierarchy;

  cv::findContours(bitmap, contours, hierarchy, cv::RETR_LIST,
                   cv::CHAIN_APPROX_SIMPLE);

  const size_t num_contours =
      std::min(contours.size(), static_cast<size_t>(max_candidates));

  std::vector<std::vector<std::vector<int>>> boxes;

  for (size_t _i = 0; _i < num_contours; _i++) {
    float ssid;
    cv::RotatedRect box = cv::minAreaRect(contours[_i]);
    auto array = get_mini_boxes(box, ssid);

    if (ssid < min_size) {
      continue;
    }

    float score;
    score = box_score_fast(array, pred);
    // end box_score_fast
    if (score < box_thresh) {
      continue;
    }

    // start for unclip
    cv::RotatedRect points = unclip(array);
    // end for unclip

    cv::RotatedRect clipbox = points;
    auto cliparray = get_mini_boxes(clipbox, ssid);

    if (ssid < min_size + 2)
      continue;

    int dest_width = pred.cols;
    int dest_height = pred.rows;
    std::vector<std::vector<int>> intcliparray;

    for (int num_pt = 0; num_pt < 4; num_pt++) {
      std::vector<int> a{int(clampf(roundf(cliparray[num_pt].x / float(width) *
                                           float(dest_width)),
                                    0, float(dest_width))),
                         int(clampf(roundf(cliparray[num_pt].y /
                                           float(height) * float(dest_height)),
                                    0, float(dest_height)))};
      intcliparray.emplace_back(std::move(a));
    }
    boxes.emplace_back(std::move(intcliparray));

  } // end for
  return boxes;
}

int _max(int a, int b) { return a >= b ? a : b; }

int _min(int a, int b) { return a >= b ? b : a; }

std::vector<std::vector<std::vector<int>>>
filter_tag_det_res(const std::vector<std::vector<std::vector<int>>> &o_boxes,
                   float ratio_h, float ratio_w, const cv::Mat &srcimg) {
  int oriimg_h = srcimg.rows;
  int oriimg_w = srcimg.cols;
  std::vector<std::vector<std::vector<int>>> boxes{o_boxes};
  std::vector<std::vector<std::vector<int>>> root_points;
  for (int n = 0; n < boxes.size(); n++) {
    boxes[n] = order_points_clockwise(boxes[n]);
    for (int m = 0; m < boxes[0].size(); m++) {
      boxes[n][m][0] /= ratio_w;
      boxes[n][m][1] /= ratio_h;

      boxes[n][m][0] = int(_min(_max(boxes[n][m][0], 0), oriimg_w - 1));
      boxes[n][m][1] = int(_min(_max(boxes[n][m][1], 0), oriimg_h - 1));
    }
  }

  for (int n = 0; n < boxes.size(); n++) {
    int rect_width, rect_height;
    rect_width = int(sqrt(pow(boxes[n][0][0] - boxes[n][1][0], 2) +
                          pow(boxes[n][0][1] - boxes[n][1][1], 2)));
    rect_height = int(sqrt(pow(boxes[n][0][0] - boxes[n][3][0], 2) +
                           pow(boxes[n][0][1] - boxes[n][3][1], 2)));
    if (rect_width <= 10 || rect_height <= 10)
      continue;
    root_points.push_back(boxes[n]);
  }
  return root_points;
}
