/*
 * AbstractSkyClockFragment.kt
 *
 * Copyright 2020 Yasuhiro Yamakawa <withlet11@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.withlet11.skyclock.fragment

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import io.github.withlet11.skyclock.MainActivity
import io.github.withlet11.skyclock.R
import io.github.withlet11.skyclock.PeriodicalUpdater
import io.github.withlet11.skyclock.model.SkyViewModel
import io.github.withlet11.skyclock.view.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

abstract class AbstractSkyClockFragment : Fragment(), MainActivity.LocationChangeObserver {
    private var locationChangeSubject: MainActivity? = null
    private lateinit var periodicalUpdater: PeriodicalUpdater

    // models
    private lateinit var skyViewModel: SkyViewModel

    // views
    private lateinit var skyPanel: SkyPanel
    private lateinit var sunPanel: SunPanel
    private lateinit var horizonPanel: HorizonPanel
    private lateinit var clockBasePanel: ClockBasePanel
    private lateinit var clockHandsPanel: ClockHandsPanel
    private lateinit var clockFrame: FrameLayout

    // geometry parameters
    private var isZoomed = false
        set(value) {
            field = value
            skyPanel.isZoomed = value
            sunPanel.isZoomed = value
            horizonPanel.isZoomed = value
            clockBasePanel.isZoomed = value
            clockHandsPanel.isZoomed = value
            adjustFrameLayoutPosition()
            scrollPanelToCenter()
        }

    private var scrollableHorizonMin = 0
    private var scrollableVerticalMin = 0
    private var scrollableHorizonMax = 0
    private var scrollableVerticalMax = 0

    // touch position and time
    private var firstActionX = 0f
    private var firstActionY = 0f
    private var previousActionX = 0f
    private var previousActionY = 0f
    private var clickCount = 0
    private var previousClickTime = 0L
    private var isDraggingSun = false

    // visibility
    private var isClockHandsVisible
        get() = locationChangeSubject?.isClockHandsVisible ?: true
        set(value) {
            clockHandsPanel.isVisible = value
            locationChangeSubject?.isClockHandsVisible = value
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // set this fragment to subject as an observer
        (context as? MainActivity)?.addObserver(this)
        locationChangeSubject = context as? MainActivity
    }

    abstract fun prepareViewModel(
        context: Context,
        latitude: Double,
        longitude: Double
    ): SkyViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_clock, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        skyPanel = view.findViewById(R.id.skyPanel)
        sunPanel = view.findViewById(R.id.sunPanel)
        horizonPanel = view.findViewById(R.id.horizonPanel)
        clockBasePanel = view.findViewById(R.id.clockBasePanel)
        clockHandsPanel = view.findViewById(R.id.clockHandsPanel)
        clockFrame = view.findViewById(R.id.frameLayout)

        // get argument
        val args = arguments
        val latitude = args?.getDouble("LATITUDE") ?: 45.0
        val longitude = args?.getDouble("LONGITUDE") ?: 0.0
        clockHandsPanel.isVisible = args?.getBoolean("CLOCK_HANDS_VISIBILITY") ?: true

        skyViewModel = prepareViewModel(activity?.applicationContext!!, latitude, longitude)

        setPeriodicalUpdater()
        setOnTapListenerToPanel()
        setViewObserver()
    }

    override fun onStart() {
        super.onStart()
        prepareClock()
    }

    override fun onResume() {
        super.onResume()
        changeLocation(skyViewModel.latitude, skyViewModel.longitude)
        val visibility = isClockHandsVisible
        isClockHandsVisible = true
        updateClock()
        isClockHandsVisible = visibility
        periodicalUpdater.timerSet()
    }

    override fun onPause() {
        periodicalUpdater.stopTimerTask()
        super.onPause()
    }

    override fun onDestroyView() {
        locationChangeSubject?.removeObserver(this)
        super.onDestroyView()
    }

    private fun toggleZoom() {
        isZoomed = !isZoomed
        clickCount = 0
        previousClickTime = 0L
    }

    private fun showOrHideClockHandsPanel() {
        clickCount = 0
        isClockHandsVisible = !isClockHandsVisible
        if (isClockHandsVisible) {
            updateClock()
            updateSkyPanel()
        } else {
            clockHandsPanel.invalidate()
        }
    }

    private fun recordFirstClick() {
        clickCount = 1
        previousClickTime = SystemClock.elapsedRealtime()
    }

    /** Sets OnClickListener and OnTouchListener to [clockHandsPanel]. */
    private fun setOnTapListenerToPanel() {
        clockHandsPanel.setOnClickListener {
            when {
                clickCount > 0 && SystemClock.elapsedRealtime() - previousClickTime < 200L -> toggleZoom()
                clockHandsPanel.isCenter(firstActionX to firstActionY) -> showOrHideClockHandsPanel()
                else -> recordFirstClick()
            }
        }

        clockHandsPanel.setOnTouchListener { v, e ->
            when (e?.action) {
                MotionEvent.ACTION_DOWN -> {
                    previousActionX = e.x
                    previousActionY = e.y
                    firstActionX = e.x
                    firstActionY = e.y
                    isDraggingSun =
                        !isClockHandsVisible && sunPanel.isOnAnalemma(e.x to e.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingSun) {
                        val rotate = sunPanel.getAngle(e.x, e.y)
                        changeTime(rotate)
                    } else {
                        scrollPanel(e.x, e.y)
                        previousActionX = e.x
                        previousActionY = e.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (e.eventTime - e.downTime < 200L &&
                        (firstActionX - e.x).pow(2) + (firstActionY - e.y).pow(2) < 5f
                    ) { // when click happens
                        v?.performClick()
                    } else { // when drag or press is ended
                        clickCount = 0
                        previousClickTime = 0L
                        if (isDraggingSun) {
                            val rotate = sunPanel.getAngle(e.x, e.y)
                            changeTime(rotate)
                            isDraggingSun = false
                        } else {
                            scrollPanel(e.x, e.y)
                        }
                    }
                }
            }

            true
        }
    }

    /**
     * Sets an observer to get the view geometries after the view is created or the view
     * geometries is changed.
     */
    private fun setViewObserver() {
        val observer = clockHandsPanel.viewTreeObserver
        observer.addOnGlobalLayoutListener {
            adjustFrameLayoutPosition()
            scrollPanelToCenter()
        }
    }

    /**
     * Sets [PeriodicalUpdater] as a periodical updater.
     */
    private fun setPeriodicalUpdater() {
        periodicalUpdater = PeriodicalUpdater(this)
    }

    /**
     * Gets geometries of [clockHandsPanel] and adjust positions of [clockFrame] and panels.
     */
    private fun adjustFrameLayoutPosition() {
        with(clockHandsPanel) {
            val totalDifference = wideSideLength - narrowSideLength
            val halfOfDifference = totalDifference / 2
            when {
                isZoomed -> {
                    if (clockHandsPanel.isLandScape) {
                        scrollableHorizonMin = 0
                        scrollableHorizonMax = 0
                        scrollableVerticalMin = -halfOfDifference
                        scrollableVerticalMax = halfOfDifference
                    } else {
                        scrollableHorizonMin = -halfOfDifference
                        scrollableHorizonMax = halfOfDifference
                        scrollableVerticalMin = 0
                        scrollableVerticalMax = 0
                    }
                    0 to 0
                }
                clockHandsPanel.isLandScape -> {
                    scrollableHorizonMin = -totalDifference
                    scrollableHorizonMax = 0
                    scrollableVerticalMin = 0
                    scrollableVerticalMax = 0
                    0 to -halfOfDifference
                }
                else -> {
                    scrollableHorizonMin = 0
                    scrollableHorizonMax = 0
                    scrollableVerticalMin = -totalDifference
                    scrollableVerticalMax = 0
                    -halfOfDifference to 0
                }
            }
        }.let { (framePositionX, framePositionY) ->
            clockFrame.scrollX = framePositionX
            clockFrame.scrollY = framePositionY
        }
    }

    /**
     * Calculates the scroll position from a touch position, and change the positions of panels.
     */
    private fun scrollPanel(endX: Float, endY: Float) {
        val x = max(
            min(clockBasePanel.scrollX + (previousActionX - endX).toInt(), scrollableHorizonMax),
            scrollableHorizonMin
        )
        val y = max(
            min(clockBasePanel.scrollY + (previousActionY - endY).toInt(), scrollableVerticalMax),
            scrollableVerticalMin
        )
        scrollPanelInRange(x, y)
    }

    private fun scrollPanelToCenter() {
        val x = (scrollableHorizonMax + scrollableHorizonMin) / 2
        val y = (scrollableVerticalMax + scrollableVerticalMin) / 2
        scrollPanelInRange(x, y)
    }

    private fun scrollPanelInRange(x: Int, y: Int) {
        listOf(clockBasePanel, clockHandsPanel, skyPanel, sunPanel, horizonPanel).forEach {
            it.scrollX = x
            it.scrollY = y
        }
    }

    private fun prepareClock() {
        setStarDataList()
        setHorizonPanel()
        setClockBasePanel()
        updateClock()
    }

    private fun setStarDataList() {
        with(skyViewModel) {
            skyPanel.set(
                starGeometryList,
                constellationLineList,
                equatorial,
                ecliptic,
                tenMinuteGridStep
            )

            sunPanel.set(
                analemma,
                monthlySunPositionList,
                currentSunPosition,
                tenMinuteGridStep
            )
        }
    }

    private fun setHorizonPanel() {
        with(skyViewModel) { horizonPanel.set(horizon, altAzimuth, directionLetters) }
    }

    private fun setClockBasePanel() {
        with(skyViewModel) { clockBasePanel.set(offset, direction, dateList) }
    }

    fun updateClock() {
        if (isClockHandsVisible) {
            with(skyViewModel) {
                setCurrentTime()
                clockHandsPanel.set(hour, minute, second)
                skyPanel.siderealAngle = siderealAngle
                sunPanel.hourAngle = solarAngle
            }
            clockHandsPanel.invalidate()
        }
    }

    fun updateSkyPanel() {
        if (isClockHandsVisible) {
            skyPanel.invalidate()
            sunPanel.invalidate()
        }
    }

    /**
     * Receives location change signal from MainActivity.
     * Implementation of [MainActivity.LocationChangeObserver.onLocationChange]
     */
    override fun onLocationChange(latitude: Double, longitude: Double) {
        changeLocation(latitude, longitude)
    }

    /**
     * Sets location to [skyViewModel] and [horizonPanel].
     */
    private fun changeLocation(latitude: Double, longitude: Double) {
        skyViewModel.changeLocation(latitude, longitude)
        setHorizonPanel()
    }

    /**
     * Changes local mean time by using the rotate angle of the Sun and update the sky view.
     * @param rotate the rotate angle of the Sun (degrees)
     */
    private fun changeTime(rotate: Float) {
        with(skyViewModel) {
            setDateWithFixedSiderealTime(rotate)
            clockBasePanel.dateList = dateList
            skyPanel.siderealAngle = siderealAngle
            sunPanel.setHourAngleAndCurrentPosition(solarAngle, currentSunPosition)
        }
        clockBasePanel.invalidate()
        skyPanel.invalidate()
        sunPanel.invalidate()
    }
}
