#!/usr/bin/env bash

android_default_sdk_root() {
  printf '%s\n' "$HOME/Android/Sdk"
}

resolve_android_project_dir() {
  local project_root="$1"
  local canonical_dir="$project_root/platform/android"
  local legacy_dir="$project_root/android"

  if [[ -d "$canonical_dir" ]]; then
    printf '%s\n' "$canonical_dir"
    return 0
  fi

  printf '%s\n' "$legacy_dir"
}

resolve_android_sdk_root() {
  local project_root="$1"
  local android_dir
  android_dir="$(resolve_android_project_dir "$project_root")"
  local sdk_root="${ANDROID_SDK_ROOT:-$(android_default_sdk_root)}"

  if [[ -f "$android_dir/local.properties" ]]; then
    local sdk_dir
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$android_dir/local.properties" | tail -n 1)"
    if [[ -n "$sdk_dir" ]]; then
      sdk_root="$sdk_dir"
    fi
  fi

  printf '%s\n' "$sdk_root"
}

resolve_adb_bin() {
  local project_root="$1"
  if [[ -n "${ADB:-}" ]]; then
    printf '%s\n' "$ADB"
    return 0
  fi

  local sdk_root
  sdk_root="$(resolve_android_sdk_root "$project_root")"
  if [[ -x "$sdk_root/platform-tools/adb" ]]; then
    printf '%s\n' "$sdk_root/platform-tools/adb"
    return 0
  fi

  printf '%s\n' "adb"
}
