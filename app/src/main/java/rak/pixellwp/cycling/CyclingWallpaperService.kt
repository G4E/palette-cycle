package rak.pixellwp.cycling

import android.content.*
import android.graphics.Rect
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import androidx.core.view.GestureDetectorCompat
import androidx.preference.PreferenceManager
import rak.pixellwp.cycling.jsonLoading.ImageLoadedListener
import rak.pixellwp.cycling.jsonLoading.ImageLoader
import rak.pixellwp.cycling.jsonModels.ImageInfo
import rak.pixellwp.cycling.models.TimelineImage
import rak.pixellwp.cycling.models.getMilliFromSeconds
import rak.pixellwp.cycling.models.getSecondsFromHour
import java.util.*
import kotlin.math.max
import kotlin.math.min

enum class ImageType(val stringValue: String) {
    TIMELINE(TIMELINE_IMAGE), COLLECTION(IMAGE_COLLECTION), SINGLE(SINGLE_IMAGE)
}

fun String?.toImageType(): ImageType {
    return ImageType.values().firstOrNull { it.stringValue == this } ?: ImageType.TIMELINE
}

class CyclingWallpaperService : WallpaperService() {
    private val logTag = "CyclingWallpaperService"
    private val imageLoader: ImageLoader by lazy { ImageLoader(this) }

    override fun onCreateEngine(): Engine {
        return CyclingWallpaperEngine()
    }

    inner class CyclingWallpaperEngine : Engine(), ImageLoadedListener {
        init {
            imageLoader.addLoadListener(this)
        }

        private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@CyclingWallpaperService)

        private var imageCollection = prefs.getString(IMAGE_COLLECTION, "") ?: "Seascape"
        private var singleImage = prefs.getString(SINGLE_IMAGE, "") ?: "CORAL"
        private var timelineImage = prefs.getString(TIMELINE_IMAGE, "") ?: "V26"
        private val defaultImage = ImageInfo("DefaultImage", "DefaultImage", 0)
        private var currentImage = defaultImage
        private var currentImageType = ImageType.TIMELINE

        private var drawRunner = PaletteDrawer(this, imageLoader.loadImage(defaultImage))

        private var imageSrc = Rect(prefs.getInt(LEFT, 0), prefs.getInt(TOP, 0), prefs.getInt(RIGHT, drawRunner.image.getImageWidth()), prefs.getInt(BOTTOM, drawRunner.image.getImageHeight()))
        var screenDimensions = Rect(imageSrc)
        private var screenOffset: Float = 0f
        private var parallax = prefs.getBoolean(PARALLAX, true)
        private var overrideTimeline = prefs.getBoolean(OVERRIDE_TIMELINE, false)
        private var overrideTime = 500L
        private var scaleFactor = prefs.getFloat(SCALE_FACTOR, 5.3f)
        private var minScaleFactor = 0.1f

        private var lastHourChecked = prefs.getInt(LAST_HOUR_CHECKED, 0)

        private val scaleDetector = ScaleGestureDetector(applicationContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector?): Boolean {
                incrementScaleFactor(detector?.scaleFactor ?: 1f)
                return true
            }
        })

        private val panDetector = GestureDetectorCompat(applicationContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
//                if (e1?.pointerCount ?: 0 > 1) {
                adjustTimeOverride(distanceX)
//                } else {
//                    adjustImageSrc(distanceX, distanceY)
//                }
                return super.onScroll(e1, e2, distanceX, distanceY)
            }
        })

        private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { preference: SharedPreferences, newValue: Any ->
            if (isPreview) {
                val prevImageCollection = imageCollection
                val prevSingleImage = singleImage
                val prevTimeline = timelineImage
                imageCollection = preference.getString(IMAGE_COLLECTION, imageCollection) ?: imageCollection
                singleImage = preference.getString(SINGLE_IMAGE, singleImage) ?: singleImage
                timelineImage = preference.getString(TIMELINE_IMAGE, timelineImage) ?: timelineImage
                parallax = preference.getBoolean(PARALLAX, parallax)
                val prefOverrideTimeline = preference.getBoolean(OVERRIDE_TIMELINE, overrideTimeline)
                val prefOverrideTimePercent = preference.getInt(OVERRIDE_TIME_PERCENT, 50)
                currentImageType = preference.getString(IMAGE_TYPE, TIMELINE_IMAGE).toImageType()

//                preference.edit().putLong()

                imageSrc = Rect(
                    preference.getInt(LEFT, imageSrc.left),
                    preference.getInt(TOP, imageSrc.top),
                    preference.getInt(RIGHT, imageSrc.right),
                    preference.getInt(BOTTOM, imageSrc.bottom)
                )

                when {
                    currentImageType == ImageType.TIMELINE && prevTimeline != timelineImage -> {
                        Log.d(logTag, "Timeline image: $timelineImage for engine $this")
                        changeTimeline()
                    }
                    currentImageType == ImageType.COLLECTION && prevImageCollection != imageCollection -> {
                        Log.d(logTag, "Image collection: $imageCollection for engine $this")
                        changeCollection()
                    }
                    prevSingleImage != singleImage -> {
                        Log.d(logTag, "Single image: $singleImage for engine $this")
                        changeImage()
                    }
                }

                updateTimelineOverride(prefOverrideTimeline, prefOverrideTimePercent)

            } else {
                reloadPrefs()
            }
        }

        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (lastHourChecked != hour) {
                    Log.d(logTag, "Hour passed ($lastHourChecked > $hour). Assessing possible image change")
                    lastHourChecked = hour
                    prefs.edit().putInt(LAST_HOUR_CHECKED, lastHourChecked).apply()
                    if (imageCollection != "") {
                        changeCollection()
                    }
                }
            }
        }

        init {
            changeImage(loadInitialImage())
            downloadFirstTimeImage()
            drawRunner.startDrawing()
        }

        private fun loadInitialImage(): ImageInfo {
            Log.v(logTag, "Load initial image img= $singleImage, collection= $imageCollection, timeline= $timelineImage, drawer= ${drawRunner.id}")
            return when {
                currentImageType == ImageType.TIMELINE && timelineImage != "" -> imageLoader.getImageInfoForTimeline(timelineImage)
                currentImageType == ImageType.COLLECTION && imageCollection != "" -> imageLoader.getImageInfoForCollection(imageCollection)
                currentImageType == ImageType.SINGLE && singleImage != "" -> imageLoader.getImageInfoForImage(singleImage)
                else -> defaultImage
            }
        }

        private fun downloadFirstTimeImage() {
            if (imageCollection == "" && singleImage == "" && timelineImage == "") {
                imageCollection = "Waterfall"
                changeCollection()
            }
        }

        private fun changeCollection() {
            if (imageCollection.isNotBlank()) {
                val image = imageLoader.getImageInfoForCollection(imageCollection)
                changeImage(image)
            }
        }

        private fun changeImage() {
            if (singleImage.isNotBlank()) {
                val image = imageLoader.getImageInfoForImage(singleImage)
                changeImage(image)
            }
        }

        private fun changeTimeline() {
            if (timelineImage.isNotBlank()) {
                val image = imageLoader.getImageInfoForTimeline(timelineImage)
                changeImage(image)
            }
        }

        private fun changeImage(image: ImageInfo) {
            if (image != currentImage) {
                Log.d(logTag, "Changing from ${currentImage.name} to ${image.name}.")
                if (imageLoader.imageIsReady(image)) {
                    currentImage = image
                    if (image.isTimeline) {
                        drawRunner.image = imageLoader.loadTimelineImage(image)
                        if (drawRunner.image is TimelineImage && overrideTimeline) {
                            (drawRunner.image as TimelineImage).setTimeOverride(overrideTime)
                        }
                    } else {
                        drawRunner.image = imageLoader.loadImage(image)
                    }
                    determineMinScaleFactor()

                } else {
                    imageLoader.downloadImage(image)
                }
            }
        }

        override fun imageLoadComplete(image: ImageInfo) {
            changeImage(image)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            PreferenceManager.getDefaultSharedPreferences(applicationContext).registerOnSharedPreferenceChangeListener(preferenceListener)
            registerReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
            super.onCreate(surfaceHolder)
        }

        override fun onDestroy() {
            drawRunner.stop()
            unregisterReceiver(timeReceiver)
            PreferenceManager.getDefaultSharedPreferences(applicationContext).unregisterOnSharedPreferenceChangeListener(preferenceListener)
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (isPreview) {
                scaleDetector.onTouchEvent(event)
                panDetector.onTouchEvent(event)
                super.onTouchEvent(event)
                drawRunner.drawNow()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                reloadPrefs()
            }
            drawRunner.setVisible(visible)
        }

        private fun reloadPrefs() {
            imageCollection = prefs.getString(IMAGE_COLLECTION, "") ?: imageCollection
            singleImage = prefs.getString(SINGLE_IMAGE, "") ?: singleImage
            timelineImage = prefs.getString(TIMELINE_IMAGE, "") ?: timelineImage
            val prefOverrideTimeline = prefs.getBoolean(OVERRIDE_TIMELINE, overrideTimeline)
            val prefOverrideTimePercent = prefs.getInt(OVERRIDE_TIME_PERCENT, 50)
            currentImageType = prefs.getString(IMAGE_TYPE, TIMELINE_IMAGE).toImageType()

            parallax = prefs.getBoolean(PARALLAX, parallax)

            imageSrc = Rect(
                prefs.getInt(LEFT, imageSrc.left),
                prefs.getInt(TOP, imageSrc.top),
                prefs.getInt(RIGHT, imageSrc.right),
                prefs.getInt(BOTTOM, imageSrc.bottom)
            )

            updateTimelineOverride(prefOverrideTimeline, prefOverrideTimePercent)

            when {
                currentImageType == ImageType.TIMELINE && timelineImage != "" -> changeTimeline()
                currentImageType == ImageType.COLLECTION && imageCollection != "" -> changeCollection()
                currentImageType == ImageType.SINGLE && singleImage != "" -> changeImage()
            }
        }

        private fun adjustTimeOverride(distanceX: Float) {
            val prefOverrideTimeline = prefs.getBoolean(OVERRIDE_TIMELINE, overrideTimeline)
            val newOverrideTime = overrideTime + distanceX.toLong() * 10000
            prefs.edit().putLong(OVERRIDE_TIME, newOverrideTime).apply()
            updateTimelineOverride(prefOverrideTimeline, newOverrideTime)
        }

        private fun updateTimelineOverride(prefOverrideTimeline: Boolean, dayPercent: Int) {
            val newOverrideTime = getMilliFromSeconds(getSecondsFromHour(24)) * dayPercent / 100
            updateTimelineOverride(prefOverrideTimeline, newOverrideTime)
        }

        private fun updateTimelineOverride(prefOverrideTimeline: Boolean, newOverrideTime: Long) {
            if (drawRunner.image is TimelineImage) {
                val image: TimelineImage = drawRunner.image as TimelineImage
                if (prefOverrideTimeline != overrideTimeline || newOverrideTime != image.getOverrideTime()) {
                    if (prefOverrideTimeline) {
                        image.setTimeOverride(newOverrideTime)
                    } else {
                        image.stopTimeOverride()
                    }
                    overrideTimeline = prefOverrideTimeline
                    overrideTime = newOverrideTime
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            drawRunner.stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            drawRunner.startDrawing()
            super.onSurfaceCreated(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            screenDimensions = Rect(0, 0, width, height)
            determineMinScaleFactor()
            if (orientationHasChanged(width, height)) {
                adjustImageSrc(0f, 0f)
            }
            super.onSurfaceChanged(holder, format, width, height)
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            screenOffset = xOffset
            drawRunner.drawNow()
        }

        fun getOffsetImage(): Rect {
            if (parallax && !isPreview) {
                val totalPossibleOffset = drawRunner.image.getImageWidth() - imageSrc.width()
                val offsetPixels = totalPossibleOffset * screenOffset
                val left = offsetPixels.toInt()
                return Rect(left, imageSrc.top, left + imageSrc.width(), imageSrc.bottom)
            }
            return imageSrc
        }

        private fun adjustImageSrc(distanceX: Float, distanceY: Float) {
            val overlapLeft: Float = drawRunner.image.getImageWidth() - screenDimensions.width() / scaleFactor
            val overLapTop: Float = drawRunner.image.getImageHeight() - screenDimensions.height() / scaleFactor

            val left = clamp(imageSrc.left + distanceX / scaleFactor, 0f, overlapLeft)
            val top = clamp(imageSrc.top + distanceY / scaleFactor, 0f, overLapTop)

            val right = left + screenDimensions.width() / scaleFactor
            val bottom = top + screenDimensions.height() / scaleFactor

            imageSrc = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            prefs.edit()
                .putInt(LEFT, imageSrc.left)
                .putInt(TOP, imageSrc.top)
                .putInt(RIGHT, imageSrc.right)
                .putInt(BOTTOM, imageSrc.bottom).apply()
        }

        private fun incrementScaleFactor(incrementFactor: Float) {
            scaleFactor *= incrementFactor
            scaleFactor = max(minScaleFactor, min(scaleFactor, 10f))
            prefs.edit().putFloat(SCALE_FACTOR, scaleFactor).apply()
        }

        private fun determineMinScaleFactor() {
            //Find the smallest scale factor that leaves no border on one side
            val w: Float = screenDimensions.width() / drawRunner.image.getImageWidth().toFloat()
            val h: Float = screenDimensions.height() / drawRunner.image.getImageHeight().toFloat()
            minScaleFactor = max(w, h)
        }

        private fun clamp(value: Float, min: Float, max: Float): Float {
            return min(max(value, min), max)
        }

        private fun orientationHasChanged(width: Int, height: Int) =
            (imageSrc.width() > imageSrc.height()) != (width > height)
    }
}