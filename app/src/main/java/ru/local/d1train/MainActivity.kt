package ru.local.d1train

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val api = RaspApi()
    private lateinit var clock: TextView
    private lateinit var outboundList: LinearLayout
    private lateinit var inboundList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg
        setContentView(createLayout())
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun createLayout(): View {
        val sidePadding = dp(18)
        val topPadding = dp(44)
        val bottomPadding = dp(18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            setPadding(sidePadding, topPadding, sidePadding, bottomPadding)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(sidePadding, topPadding, sidePadding, bottomPadding + bars.bottom)
                    insets
                }
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clock = label("", 13f, Palette.muted, false)
        header.addView(clock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Обновить", Palette.card, Palette.text).apply { setOnClickListener { refresh() } })
        root.addView(header)

        val board = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        outboundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inboundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        board.addView(directionPanel("Окружная", "Белорусский вокзал", "в центр", outboundList))
        board.addView(space(10))
        board.addView(directionPanel("Белорусский вокзал", "Окружная", "из центра", inboundList))
        root.addView(board)
        return ScrollView(this).apply {
            setBackgroundColor(Palette.bg)
            isFillViewport = false
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun directionPanel(from: String, to: String, hint: String, list: LinearLayout): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = rounded(Palette.panel, dp(22), Palette.stroke, 1)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val marker = TextView(this).apply {
            text = "D1"
            setTextColor(Palette.bg)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 15f
            gravity = Gravity.CENTER
            background = rounded(Palette.accent, dp(10), 0, 0)
        }
        row.addView(marker, LinearLayout.LayoutParams(dp(44), dp(34)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        names.addView(label("$from -> $to", 19f, Palette.text, true))
        names.addView(label(hint, 12f, Palette.muted, false))
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)

        list.setPadding(0, dp(2), 0, 0)
        panel.addView(list)
        return panel
    }

    private fun refresh() {
        val now = ZonedDateTime.now(MOSCOW_ZONE)
        clock.text = "Московское время: ${TIME_FORMAT.format(now)}"
        outboundList.removeAllViews()
        inboundList.removeAllViews()
        outboundList.addView(loadingRow())
        inboundList.addView(loadingRow())

        loadDirection(Direction.OKR_TO_BEL, outboundList)
        loadDirection(Direction.BEL_TO_OKR, inboundList)
    }

    private fun loadDirection(direction: Direction, container: LinearLayout) {
        io.execute {
            val result = runCatching { api.trains(direction).take(5) }
            main.post {
                container.removeAllViews()
                result.onSuccess { trains ->
                    if (trains.isEmpty()) {
                        container.addView(emptyRow("Ближайших рейсов нет"))
                    } else {
                        trains.forEach { train -> container.addView(trainCard(direction, train)) }
                    }
                }.onFailure {
                    container.addView(emptyRow("Не удалось загрузить: ${it.shortMessage()}"))
                }
            }
        }
    }

    private fun trainCard(direction: Direction, train: Train): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Palette.card, dp(18), Palette.stroke, 1)
            isClickable = true
            setOnClickListener { showStops(direction, train) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(pill(train.minutesLabel, train.badgeColor, Palette.bg))
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        times.addView(label(train.departureTime, 30f, Palette.text, true))
        times.addView(label("  ${train.durationMin} мин  ", 13f, Palette.muted, false))
        times.addView(label(train.arrivalTime, 24f, Palette.dim, false))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(times)
        card.addView(top)

        val subtypeColor = train.subtypeColor ?: Palette.green
        card.addView(label(train.subtypeTitle.ifBlank { "МЦД D1" }, 15f, subtypeColor, true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        card.addView(label(train.title, 14f, Palette.textSoft, false).apply { maxLines = 2 })

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(10), 0, 0)
        card.layoutParams = lp
        return card
    }

    private fun showStops(direction: Direction, train: Train) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Palette.panel, dp(24), Palette.stroke, 1)
        }
        val title = label("${train.departureTime} -> ${train.arrivalTime}", 25f, Palette.text, true)
        body.addView(title)
        body.addView(label("${direction.fromShort} -> ${direction.toShort}", 14f, Palette.muted, false))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        list.addView(loadingRow())
        val scroller = ScrollView(this).apply { addView(list) }
        body.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(460)))
        body.addView(pill("Закрыть", Palette.card, Palette.text).apply {
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        dialog.setContentView(body)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        io.execute {
            val result = runCatching { api.stops(train).filterNot { it.noStop || it.technical } }
            main.post {
                list.removeAllViews()
                result.onSuccess { stops ->
                    if (stops.isEmpty()) {
                        list.addView(emptyRow("Остановки не пришли"))
                    } else {
                        stops.forEachIndexed { index, stop -> list.addView(stopRow(stop, index == stops.lastIndex)) }
                    }
                }.onFailure {
                    list.addView(emptyRow("Не удалось открыть расписание: ${it.shortMessage()}"))
                }
            }
        }
    }

    private fun stopRow(stop: Stop, last: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val dot = TextView(this).apply {
            text = if (stop.isEndpoint) "●" else "○"
            textSize = 18f
            setTextColor(if (stop.isEndpoint) Palette.accent else Palette.muted)
            gravity = Gravity.CENTER
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(label(stop.title.cleanTitle(), 15f, Palette.text, stop.isEndpoint))
        info.addView(label(stop.time, 12f, Palette.muted, false))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!last) row.background = underline()
        return row
    }

    private fun loadingRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(10))
        }
        row.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(label("  Загружаю...", 14f, Palette.muted, false))
        return row
    }

    private fun emptyRow(text: String): View = label(text, 14f, Palette.muted, false).apply {
        setPadding(0, dp(18), 0, dp(18))
    }

    private fun label(textValue: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = sp
            setTextColor(color)
            includeFontPadding = true
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun pill(textValue: String, bg: Int, fg: Int): TextView =
        label(textValue, 14f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(bg, dp(14), 0, 0)
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }

    private fun underline(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Palette.stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun Throwable.shortMessage(): String = message?.take(90) ?: javaClass.simpleName

}

private val MOSCOW_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private object Palette {
    val bg = Color.rgb(18, 19, 22)
    val panel = Color.rgb(30, 32, 37)
    val card = Color.rgb(43, 46, 52)
    val stroke = Color.rgb(66, 69, 76)
    val text = Color.rgb(246, 247, 241)
    val textSoft = Color.rgb(219, 221, 214)
    val muted = Color.rgb(150, 154, 160)
    val dim = Color.rgb(112, 116, 123)
    val accent = Color.rgb(246, 200, 76)
    val green = Color.rgb(93, 216, 106)
}

private enum class Direction(
    val fromKey: String,
    val fromTitle: String,
    val fromSlug: String,
    val toKey: String,
    val toTitle: String,
    val toSlug: String,
    val fromShort: String,
    val toShort: String,
    val distance: Double
) {
    OKR_TO_BEL(
        "s9601830",
        "Окружная",
        "okruzhnaya-platform",
        "s2000006",
        "Москва (Белорусский вокзал)",
        "moscow-belorusskaya",
        "Окружная",
        "Белорусский",
        7.927423230124116
    ),
    BEL_TO_OKR(
        "s2000006",
        "Москва (Белорусский вокзал)",
        "moscow-belorusskaya",
        "s9601830",
        "Окружная",
        "okruzhnaya-platform",
        "Белорусский",
        "Окружная",
        7.927423230124116
    )
}

private data class Train(
    val number: String,
    val title: String,
    val canonicalUid: String,
    val departureApi: String,
    val stationFrom: String,
    val stationTo: String,
    val departure: ZonedDateTime,
    val arrival: ZonedDateTime,
    val platform: String,
    val subtypeTitle: String,
    val subtypeColor: Int?
) {
    val departureTime: String = TIME_FORMAT.format(departure)
    val arrivalTime: String = TIME_FORMAT.format(arrival)
    val durationMin: Long = Duration.between(departure, arrival).toMinutes().coerceAtLeast(0)
    val minutesLabel: String
        get() {
            val minutes = Duration.between(ZonedDateTime.now(MOSCOW_ZONE), departure).toMinutes()
            return when {
                minutes <= 0 -> "сейчас"
                minutes < 60 -> "через ${minutes} мин"
                else -> TIME_FORMAT.format(departure)
            }
        }
    val badgeColor: Int
        get() {
            val minutes = Duration.between(ZonedDateTime.now(MOSCOW_ZONE), departure).toMinutes()
            return if (minutes in 0..10) Color.rgb(255, 82, 96) else Palette.accent
        }
}

private data class Stop(
    val title: String,
    val time: String,
    val platform: String,
    val isEndpoint: Boolean,
    val technical: Boolean,
    val noStop: Boolean
)

private class RaspApi {
    fun trains(direction: Direction): List<Train> {
        val response = post(batchSearch(direction))
        val segments = response.getJSONArray("data")
            .getJSONObject(0)
            .getJSONObject("data")
            .getJSONObject("search")
            .getJSONArray("segments")
        val now = ZonedDateTime.now(MOSCOW_ZONE)
        val trains = mutableListOf<Train>()
        for (i in 0 until segments.length()) {
            val item = segments.getJSONObject(i)
            val dep = parseApiTime(item.getString("departureLocalDt"))
            if (dep.isBefore(now.minusMinutes(1))) continue
            val transport = item.optJSONObject("transport")
            val subtype = transport?.optJSONObject("subtype")
            val thread = item.getJSONObject("thread")
            val from = item.getJSONObject("stationFrom")
            val to = item.getJSONObject("stationTo")
            trains += Train(
                number = item.optString("number", thread.optString("number", "")),
                title = item.optString("title"),
                canonicalUid = thread.getString("canonicalUid"),
                departureApi = dep.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                stationFrom = from.getLong("id").toString(),
                stationTo = to.getLong("id").toString(),
                departure = dep,
                arrival = parseApiTime(item.getString("arrivalLocalDt")),
                platform = from.optString("platform"),
                subtypeTitle = subtype?.optString("title").orEmpty(),
                subtypeColor = subtype?.optString("titleColor")?.takeIf { it.startsWith("#") }?.let { Color.parseColor(it) }
            )
        }
        return trains.sortedBy { it.departure }.take(5)
    }

    fun stops(train: Train): List<Stop> {
        val response = post(batchThread(train))
        val stations = response.getJSONArray("data")
            .getJSONObject(0)
            .getJSONObject("data")
            .getJSONArray("rtstations")
        val stops = mutableListOf<Stop>()
        for (i in 0 until stations.length()) {
            val station = stations.getJSONObject(i)
            val time = station.optString("departureLocalDt")
                .ifBlank { station.optString("arrivalLocalDt") }
                .takeIf { it.isNotBlank() }
                ?.let { TIME_FORMAT.format(parseApiTime(it)) }
                .orEmpty()
            stops += Stop(
                title = station.optString("title"),
                time = time,
                platform = station.optString("platform"),
                isEndpoint = station.optBoolean("isStationFrom") || station.optBoolean("isStationTo"),
                technical = station.optBoolean("isTechnicalStop"),
                noStop = station.optBoolean("isNoStop")
            )
        }
        return stops
    }

    private fun post(body: JSONObject): JSONObject {
        val connection = (URL("https://rasp.yandex.ru/api/batch").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 18_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://rasp.yandex.ru")
            setRequestProperty("Referer", "https://rasp.yandex.ru/suburban/okruzhnaya-platform--moscow-belorusskaya")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) error("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun batchSearch(direction: Direction): JSONObject {
        val from = station(direction.fromKey, direction.fromTitle, direction.fromSlug)
        val to = station(direction.toKey, direction.toTitle, direction.toSlug)
        val context = JSONObject()
            .put("userInput", JSONObject().put("from", from).put("to", to))
            .put("transportType", "suburban")
            .put("from", from)
            .put("originalFrom", from)
            .put("to", to)
            .put("originalTo", to)
            .put("searchNext", false)
            .put("when", JSONObject().put("text", "на все дни").put("hint", "на все дни").put("special", "all-days").put("formatted", "на все дни"))
            .put("time", JSONObject().put("now", System.currentTimeMillis()).put("timezone", "Europe/Moscow"))
            .put("language", "ru")
            .put("searchForPastDate", false)
            .put("sameSuburbanZone", true)
            .put("distance", direction.distance)

        return JSONObject().put(
            "methods",
            JSONArray().put(
                JSONObject()
                    .put("method", "search")
                    .put(
                        "params",
                        JSONObject()
                            .put("context", context)
                            .put("isMobile", true)
                            .put("excludeTrains", false)
                            .put("nationalVersion", "ru")
                            .put("groupTrains", false)
                            .put("allowChangeContext", true)
                    )
            )
        )
    }

    private fun batchThread(train: Train): JSONObject =
        JSONObject().put(
            "methods",
            JSONArray().put(
                JSONObject()
                    .put("method", "thread2")
                    .put(
                        "params",
                        JSONObject()
                            .put("isCitySearch", false)
                            .put("threadId", train.canonicalUid)
                            .put("country", "RU")
                            .put("language", "ru")
                            .put("departureFrom", train.departureApi)
                            .put("stationFrom", train.stationFrom)
                            .put("stationTo", train.stationTo)
                    )
            )
        )

    private fun station(key: String, title: String, slug: String): JSONObject {
        val isBel = key == "s2000006"
        return JSONObject()
            .put("key", key)
            .put("title", title)
            .put("timezone", "Europe/Moscow")
            .put("country", JSONObject().put("code", "RU").put("title", "").put("railwayTimezone", "Europe/Moscow"))
            .put("region", JSONObject().put("title", "Москва и Московская область"))
            .put("settlement", JSONObject().put("title", "Москва").put("slug", "moscow").put("key", "c213"))
            .put("titleGenitive", if (isBel) "Москвы (Белорусский вокзал)" else "Окружной")
            .put("titleAccusative", if (isBel) "Москву (Белорусский вокзал)" else "Окружную")
            .put("titleLocative", if (isBel) "Москве (Белорусский вокзал)" else "Окружной")
            .put("preposition", "в")
            .put("shortTitle", if (isBel) "М-Белорусск." else "")
            .put("popularTitle", if (isBel) "Белорусский вокзал" else "")
            .put("slug", slug)
    }

    private fun parseApiTime(value: String): ZonedDateTime =
        OffsetDateTime.parse(value).atZoneSameInstant(MOSCOW_ZONE)
}

private fun String.cleanTitle(): String =
    replace("пл. ", "")
        .replace("пл. ", "")
        .replace("Москва (Белорусский вокзал)", "Белорусский вокзал")
        .replace(" (Тестовская, МЦД-1)", "")
