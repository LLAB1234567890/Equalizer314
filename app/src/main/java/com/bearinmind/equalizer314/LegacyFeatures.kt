package com.bearinmind.equalizer314

/**
 * Graveyard for retired features, kept for reference only. Nothing in this
 * file is wired up anywhere — it compiles to an empty object and does nothing
 * at runtime. The original code is preserved below as comments, together with
 * where it used to hook in, so it can be resurrected or studied later.
 */
object LegacyFeatures {

    // =====================================================================
    // Per-band L / Both / R tether picker popup (issue #53)
    // Retired 2026-07-09.
    //
    // What it did: while Channel Side EQ was on, tapping an ALREADY-SELECTED
    // band card opened a PopupMenu (Left / Both / Right) anchored to the card
    // that moved the band between the L and R channel EQs ("tether").
    // Superseded by the shared "Both" graph layer — a dedicated flat overlay
    // graph whose bands are summed into both channels' DP output.
    //
    // Where it hooked in:
    //
    // 1) MainActivity — BandToggleManager construction passed the callback:
    //
    //    bandToggleManager = BandToggleManager(
    //        this, bandToggleGroup, bandToggleGroup2, bandToggleExtraRows,
    //        bandToggleExtraScroll, bandAddButtonRow, triangleIndicator,
    //        eqGraphView, stateManager,
    //        onEqChanged, onBandCountChanged, onBandSelected,
    //        onBandReselected = { anchor, bandIdx -> showBandChannelPopup(anchor, bandIdx) }
    //    )
    //
    //    (BandToggleManager.onToggleClicked still checks its onBandReselected
    //    callback, but MainActivity no longer passes one, so it stays null and
    //    re-tapping a selected band card does nothing special.)
    //
    // 2) MainActivity — the two private functions below:
    //
    //    /** Per-band L / Both / R picker (issue #53). Opened by tapping an
    //     *  already-selected band card while Channel Side EQ is on; anchored
    //     *  to that card. The current channel is shown checked. */
    //    private fun showBandChannelPopup(anchor: View, bandIdx: Int) {
    //        if (!eqPrefs.getChannelSideEqEnabled()) return
    //        val current = stateManager.getBandChannel(bandIdx)
    //        val items = listOf(
    //            ParametricEqualizer.Channel.LEFT to "Left",
    //            ParametricEqualizer.Channel.BOTH to "Both",
    //            ParametricEqualizer.Channel.RIGHT to "Right",
    //        )
    //        val popup = android.widget.PopupMenu(this, anchor)
    //        items.forEachIndexed { i, (ch, label) ->
    //            popup.menu.add(0, i, i, label).apply {
    //                isCheckable = true
    //                isChecked = ch == current
    //            }
    //        }
    //        popup.setOnMenuItemClickListener { mi ->
    //            onBandChannelPicked(items[mi.itemId].first)
    //            true
    //        }
    //        popup.show()
    //    }
    //
    //    private fun onBandChannelPicked(channel: ParametricEqualizer.Channel) {
    //        val idx = stateManager.selectedBandIndex ?: return
    //        val movedAway = stateManager.setBandChannel(idx, channel)
    //        if (movedAway) {
    //            // Band left the active channel — refresh everything for the
    //            // (now smaller) active channel and re-highlight.
    //            rebindActiveEq()
    //            reorderToggleRows(animate = false)
    //        } else {
    //            eqGraphView.updateBandLevels()
    //            updateFilterTypeButtons(stateManager.selectedBandIndex)
    //        }
    //        // Update the dotted ghosts (channels may now diverge) and the Both
    //        // button's lit state.
    //        stateManager.getGhostEqs().let { eqGraphView.setGhostEqualizer(it.first, it.second) }
    //        eqGraphView.setOverlayEqualizer(stateManager.getGraphOverlayEq())
    //        paintChannelButtonStyles()
    //    }
    //
    // Note: the underlying tether machinery in EqStateManager
    // (getBandChannel / setBandChannel / syncBothBands / sanitizeTethers) is
    // still live — only this popup UI entry point was retired.
    // =====================================================================
}
