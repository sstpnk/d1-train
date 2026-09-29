package com.sstpnk.mcd

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private lateinit var clock: TextView
    private lateinit var contentRoot: LinearLayout
    private lateinit var board: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var outboundList: LinearLayout
    private lateinit var inboundList: LinearLayout
    private var pullStartY = -1f
    private var pullStartX = -1f

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
        contentRoot = root

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clock = label("", 13f, Palette.muted, false)
        header.addView(clock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(settingsButton())
        root.addView(header)

        board = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(board)
        scroller = ScrollView(this).apply {
            setBackgroundColor(Palette.bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroller
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pullStartX = event.rawX
                pullStartY = event.rawY
                contentRoot.animate().cancel()
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - pullStartY
                val deltaX = kotlin.math.abs(event.rawX - pullStartX)
                if (pullStartY >= 0 && deltaY > dp(8) && deltaY > deltaX) {
                    val maxPull = dp(136).toFloat()
                    val rubber = maxPull * (1f - 1f / (1f + deltaY / maxPull))
                    contentRoot.translationY = rubber
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val deltaY = event.rawY - pullStartY
                val deltaX = kotlin.math.abs(event.rawX - pullStartX)
                if (pullStartY >= 0 && deltaY > dp(120) && deltaY > deltaX) {
                    refresh()
                }
                contentRoot.animate().translationY(0f).setDuration(220).start()
                pullStartX = -1f
                pullStartY = -1f
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun directionPanel(lineId: String, from: String, to: String, hint: String, list: LinearLayout): View {
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
            text = lineId
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
        val route = selectedRoute()
        board.removeAllViews()
        if (route == null) {
            showEmptySelection()
            return
        }

        outboundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inboundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        board.addView(directionPanel(route.line.id, route.from, route.to, "туда", outboundList))
        board.addView(space(10))
        board.addView(directionPanel(route.line.id, route.to, route.from, "обратно", inboundList))

        outboundList.removeAllViews()
        inboundList.removeAllViews()
        outboundList.addView(loadingRow())
        inboundList.addView(loadingRow())

        loadDirection(RouteDirection(route.line, route.from, route.to), outboundList)
        loadDirection(RouteDirection(route.line, route.to, route.from), inboundList)
    }

    private fun loadDirection(direction: RouteDirection, container: LinearLayout) {
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

    private fun trainCard(direction: RouteDirection, train: Train): View {
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

    private fun showStops(direction: RouteDirection, train: Train) {
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

    private fun showEmptySelection() {
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = resources.displayMetrics.heightPixels - dp(180)
        }
        empty.addView(label("Выбери линию и станции", 18f, Palette.muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })
        empty.addView(pill("Выбрать станции", Palette.accent, Palette.bg).apply {
            textSize = 22f
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { showSettings() }
        })
        board.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun settingsButton(): View =
        ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_24)
            setColorFilter(Palette.text)
            background = rounded(Palette.card, dp(14), 0, 0)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Настройки"
            setOnClickListener { showSettings() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun showSettings() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val current = selectedRoute()
        var activeLine = current?.line ?: MCD_LINES.first()

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Palette.panel, dp(24), Palette.stroke, 1)
        }
        body.addView(label("Настройки маршрута", 24f, Palette.text, true))
        body.addView(label("Линия МЦД", 13f, Palette.muted, false).apply { setPadding(0, dp(14), 0, dp(4)) })

        val lineSpinner = Spinner(this)
        val fromSpinner = Spinner(this)
        val toSpinner = Spinner(this)
        val error = label("", 13f, Color.rgb(255, 116, 128), false).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }

        lineSpinner.adapter = spinnerAdapter(MCD_LINES.map { "${it.id} · ${it.title}" })
        lineSpinner.setSelection(MCD_LINES.indexOf(activeLine).coerceAtLeast(0))
        body.addView(lineSpinner)
        body.addView(label("Начальная станция", 13f, Palette.muted, false).apply { setPadding(0, dp(12), 0, dp(4)) })
        body.addView(fromSpinner)
        body.addView(label("Конечная станция", 13f, Palette.muted, false).apply { setPadding(0, dp(12), 0, dp(4)) })
        body.addView(toSpinner)
        body.addView(error)

        fun updateStations(line: McdLine, keepSelection: Boolean) {
            activeLine = line
            val adapter = spinnerAdapter(line.stations)
            fromSpinner.adapter = adapter
            toSpinner.adapter = spinnerAdapter(line.stations)
            val fromIndex = if (keepSelection) line.stations.indexOf(current?.from) else -1
            val toIndex = if (keepSelection) line.stations.indexOf(current?.to) else -1
            fromSpinner.setSelection(fromIndex.takeIf { it >= 0 } ?: line.defaultFromIndex)
            toSpinner.setSelection(toIndex.takeIf { it >= 0 } ?: line.defaultToIndex)
        }

        lineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateStations(MCD_LINES[position], keepSelection = MCD_LINES[position] == current?.line)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateStations(activeLine, keepSelection = true)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        actions.addView(settingsActionButton("Отмена", Palette.card, Palette.text).apply {
            setOnClickListener { dialog.dismiss() }
        })
        actions.addView(hSpace(12))
        actions.addView(settingsActionButton("Сохранить", Palette.accent, Palette.bg).apply {
            setOnClickListener {
                val from = fromSpinner.selectedItem?.toString().orEmpty()
                val to = toSpinner.selectedItem?.toString().orEmpty()
                if (from == to) {
                    error.text = "Выбери разные станции"
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString(PREF_LINE, activeLine.id)
                    .putString(PREF_FROM, from)
                    .putString(PREF_TO, to)
                    .apply()
                dialog.dismiss()
                refresh()
            }
        })
        body.addView(actions)

        dialog.setContentView(body)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun selectedRoute(): RouteConfig? {
        val lineId = prefs.getString(PREF_LINE, null) ?: return null
        val line = MCD_LINES.firstOrNull { it.id == lineId } ?: return null
        val from = prefs.getString(PREF_FROM, null)?.takeIf { it in line.stations } ?: return null
        val to = prefs.getString(PREF_TO, null)?.takeIf { it in line.stations && it != from } ?: return null
        return RouteConfig(line, from, to)
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Palette.text)
                    textSize = 16f
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Palette.text)
                    setBackgroundColor(Palette.card)
                    textSize = 16f
                    minHeight = dp(44)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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

    private fun settingsActionButton(textValue: String, bg: Int, fg: Int): TextView =
        label(textValue, 16f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(bg, dp(16), 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f)
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun hSpace(width: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(width), 1)
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
    val late = Color.rgb(118, 123, 132)
}

private const val PREF_LINE = "line"
private const val PREF_FROM = "from"
private const val PREF_TO = "to"

private data class McdLine(
    val id: String,
    val title: String,
    val stations: List<String>,
    val defaultFromIndex: Int,
    val defaultToIndex: Int
)

private data class RouteConfig(
    val line: McdLine,
    val from: String,
    val to: String
)

private data class RouteDirection(
    val line: McdLine,
    val from: String,
    val to: String
) {
    val fromShort: String = from.cleanTitle()
    val toShort: String = to.cleanTitle()
}

private val MCD_LINES = listOf(
    McdLine(
        id = "D1",
        title = "Белорусско-Савёловский",
        stations = listOf(
            "Лобня",
            "Шереметьевская",
            "Хлебниково",
            "Водники",
            "Долгопрудная",
            "Новодачная",
            "Марк",
            "Лианозово",
            "Бескудниково",
            "Дегунино",
            "Окружная",
            "Тимирязевская",
            "Дмитровская",
            "Москва (Савёловский вокзал)",
            "Москва (Белорусский вокзал)",
            "Беговая",
            "Москва-Сити",
            "Фили",
            "Славянский Бульвар",
            "Кунцевская",
            "Рабочий Посёлок",
            "Сетунь",
            "Немчиновка",
            "Сколково",
            "Баковка",
            "Одинцово"
        ),
        defaultFromIndex = 10,
        defaultToIndex = 14
    ),
    McdLine(
        id = "D2",
        title = "Курско-Рижский",
        stations = listOf(
            "Нахабино",
            "Аникеевка",
            "Опалиха",
            "Красногорская",
            "Павшино",
            "Пенягино",
            "Волоколамская",
            "Трикотажная",
            "Тушинская",
            "Щукинская",
            "Стрешнево",
            "Красный Балтиец",
            "Гражданская",
            "Дмитровская",
            "Марьина Роща",
            "Рижская",
            "Площадь трёх вокзалов",
            "Москва (Курский вокзал)",
            "Москва-Товарная",
            "Калитники",
            "Новохохловская",
            "Текстильщики",
            "Печатники",
            "Люблино",
            "Депо",
            "Перерва",
            "Курьяново",
            "Москворечье",
            "Царицыно",
            "Покровское",
            "Красный Строитель",
            "Битца",
            "Бутово",
            "Щербинка",
            "Остафьево",
            "Силикатная",
            "Подольск"
        ),
        defaultFromIndex = 17,
        defaultToIndex = 28
    ),
    McdLine(
        id = "D3",
        title = "Ленинградско-Казанский",
        stations = listOf(
            "Зеленоград-Крюково",
            "Малино",
            "Фирсановская",
            "Сходня",
            "Подрезково",
            "Новоподрезково",
            "Молжаниново",
            "Химки",
            "Левобережная",
            "Ховрино",
            "Грачёвская",
            "Моссельмаш",
            "Лихоборы",
            "Петровско-Разумовская",
            "Останкино",
            "Митьково",
            "Электрозаводская",
            "Сортировочная",
            "Авиамоторная",
            "Андроновка",
            "Перово",
            "Плющево",
            "Вешняки",
            "Выхино",
            "Косино",
            "Ухтомская",
            "Люберцы",
            "Панки",
            "Томилино",
            "Красково",
            "Малаховка",
            "Удельная",
            "Быково",
            "Ильинская",
            "Отдых",
            "Кратово",
            "Есенинская",
            "Фабричная",
            "Раменское",
            "Ипподром"
        ),
        defaultFromIndex = 13,
        defaultToIndex = 23
    ),
    McdLine(
        id = "D4",
        title = "Калужско-Нижегородский",
        stations = listOf(
            "Апрелевка",
            "Победа",
            "Крёкшино",
            "Санино",
            "Кокошкино",
            "Толстопальцево",
            "Лесной Городок",
            "Внуково",
            "Мичуринец",
            "Переделкино",
            "Солнечная",
            "Мещерская",
            "Очаково",
            "Аминьевская",
            "Минская",
            "Поклонная",
            "Кутузовская",
            "Москва-Сити",
            "Беговая",
            "Москва (Белорусский вокзал)",
            "Москва (Савёловский вокзал)",
            "Марьина Роща",
            "Рижская",
            "Площадь трёх вокзалов",
            "Москва (Курский вокзал)",
            "Серп и Молот",
            "Нижегородская",
            "Чухлинка",
            "Кусково",
            "Новогиреево",
            "Реутов",
            "Никольское",
            "Салтыковская",
            "Кучино",
            "Ольгино",
            "Железнодорожная"
        ),
        defaultFromIndex = 19,
        defaultToIndex = 30
    )
)

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
                minutes < 0 -> "${-minutes} мин назад"
                minutes == 0L -> "сейчас"
                minutes < 60 -> "через ${minutes} мин"
                else -> TIME_FORMAT.format(departure)
            }
        }
    val badgeColor: Int
        get() {
            val minutes = Duration.between(ZonedDateTime.now(MOSCOW_ZONE), departure).toMinutes()
            return when {
                minutes < 0 -> Palette.late
                minutes in 0..10 -> Color.rgb(255, 82, 96)
                else -> Palette.accent
            }
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
    fun trains(direction: RouteDirection): List<Train> {
        val context = parseContext(direction)
        val response = post(batchSearch(context))
        val segments = response.getJSONArray("data")
            .getJSONObject(0)
            .getJSONObject("data")
            .getJSONObject("search")
            .getJSONArray("segments")
        val now = ZonedDateTime.now(MOSCOW_ZONE)
        val cutoff = now.minusMinutes(3)
        val trains = mutableListOf<Train>()
        for (i in 0 until segments.length()) {
            val item = segments.getJSONObject(i)
            val dep = parseApiTime(item.getString("departureLocalDt"))
            if (dep.isBefore(cutoff)) continue
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

    private fun parseContext(direction: RouteDirection): JSONObject {
        val response = post(
            JSONObject().put(
                "methods",
                JSONArray().put(
                    JSONObject()
                        .put("method", "parseContext")
                        .put(
                            "params",
                            JSONObject()
                                .put("tld", "ru")
                                .put("language", "ru")
                                .put("transportType", "suburban")
                                .put("fromTitle", direction.from)
                                .put("toTitle", direction.to)
                        )
                )
            )
        )
        val data = response.getJSONArray("data").getJSONObject(0).getJSONObject("data")
        val errors = data.optJSONArray("errors")
        if (errors != null && errors.length() > 0) error("Станции не найдены")
        return data
    }

    private fun batchSearch(context: JSONObject): JSONObject {
        val from = context.getJSONObject("from")
        val to = context.getJSONObject("to")
        context
            .put("userInput", JSONObject().put("from", from).put("to", to))
            .put("searchNext", false)
            .put("when", JSONObject().put("text", "на все дни").put("hint", "на все дни").put("special", "all-days").put("formatted", "на все дни"))
            .put("time", JSONObject().put("now", System.currentTimeMillis()).put("timezone", "Europe/Moscow"))
            .put("language", "ru")
            .put("searchForPastDate", false)

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

    private fun parseApiTime(value: String): ZonedDateTime =
        OffsetDateTime.parse(value).atZoneSameInstant(MOSCOW_ZONE)
}

private fun String.cleanTitle(): String =
    replace("пл. ", "")
        .replace("пл. ", "")
        .replace("Москва (Белорусский вокзал)", "Белорусский вокзал")
        .replace(" (Тестовская, МЦД-1)", "")
