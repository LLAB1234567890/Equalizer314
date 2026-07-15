package com.bearinmind.equalizer314.autoeq

data class AutoEqEntry(
    val name: String,
    val source: String,
    val type: String,
    val rig: String,
    val path: String
)

data class AutoEqFilter(
    val filterType: String,
    val frequency: Float,
    val gain: Float,
    val q: Float
)

/**
 * Parsed representation of an APO config file.
 *
 * - [filters] — flat list of every filter in source order; always populated,
 *   authoritative for single-channel (no `Channel:` directive) files.
 * - [leftFilters] / [rightFilters] — per-channel buckets for `Channel: L`/`R`
 *   directives. Filters scoped to both (`Channel: L R` or before any directive)
 *   appear in BOTH lists.
 * - [perChannel] — true iff any `Channel: L`/`R` line appeared. When false,
 *   [filters] == [leftFilters] == [rightFilters]; callers can ignore the split.
 */
data class AutoEqProfile(
    val preampDb: Float,
    val filters: List<AutoEqFilter>,
    val leftFilters: List<AutoEqFilter> = filters,
    val rightFilters: List<AutoEqFilter> = filters,
    val perChannel: Boolean = false,
)
