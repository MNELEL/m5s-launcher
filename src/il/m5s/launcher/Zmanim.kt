package il.m5s.launcher

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

class City(val name: String, val lat: Double, val lon: Double, val candle: Int)

/** Offline halachic times (approximate, +-1-2 min). Sunrise/sunset algorithm from the Almanac for Computers. */
object Zmanim {
    val cities = listOf(
        City("ירושלים", 31.7683, 35.2137, 40),
        City("בני ברק", 32.0809, 34.8338, 18),
        City("תל אביב", 32.0853, 34.7818, 18),
        City("חיפה", 32.7940, 34.9896, 30),
        City("באר שבע", 31.2530, 34.7915, 18),
        City("בית שמש", 31.7469, 34.9881, 18),
        City("מודיעין עילית", 31.9333, 35.0417, 18),
        City("ביתר עילית", 31.6933, 35.1167, 18),
        City("אלעד", 32.0522, 34.9510, 18),
        City("פתח תקווה", 32.0840, 34.8878, 18),
        City("אשדוד", 31.8040, 34.6553, 18),
        City("אשקלון", 31.6688, 34.5743, 18),
        City("נתניה", 32.3215, 34.8532, 18),
        City("טבריה", 32.7959, 35.5310, 18),
        City("צפת", 32.9646, 35.4960, 22)
    )

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
    private fun norm(x: Double, m: Double): Double { var v = x % m; if (v < 0) v += m; return v }

    /** Local time in hours for the sun reaching [zenith] degrees, or null if it never does. */
    fun sun(dayOfYear: Int, lat: Double, lon: Double, zenith: Double, rising: Boolean, tz: Double): Double? {
        val lngHour = lon / 15
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(zenith)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        val tt = h + ra - 0.06571 * t - 6.622
        return norm(tt - lngHour, 24.0) + tz
    }

    private fun f(h: Double): String {
        val m = Math.round(h * 60).toInt()
        val mm = ((m % 1440) + 1440) % 1440
        return "%02d:%02d".format(mm / 60, mm % 60)
    }

    fun list(city: City, now: Calendar): List<Pair<String, String>> {
        val doy = now.get(Calendar.DAY_OF_YEAR)
        val tz = now.timeZone.getOffset(now.timeInMillis) / 3600000.0
        fun s(z: Double, r: Boolean) = sun(doy, city.lat, city.lon, z, r, tz)
        val rise = s(90.8333, true)
        val set = s(90.8333, false)
        if (rise == null || set == null) return emptyList()
        val alot = s(106.1, true)
        val tzeit = s(98.5, false)
        val gra = (set - rise) / 12
        val mgaStart = rise - 1.2
        val mga = ((set + 1.2) - mgaStart) / 12
        val out = ArrayList<Pair<String, String>>()
        if (now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) out.add("הדלקת נרות" to f(set - city.candle / 60.0))
        if (alot != null) out.add("עלות השחר" to f(alot))
        out.add("הנץ החמה" to f(rise))
        out.add("סוף זמן ק״ש (מג״א)" to f(mgaStart + 3 * mga))
        out.add("סוף זמן ק״ש (גר״א)" to f(rise + 3 * gra))
        out.add("סוף זמן תפילה (גר״א)" to f(rise + 4 * gra))
        out.add("חצות היום" to f(rise + 6 * gra))
        out.add("מנחה גדולה" to f(rise + 6.5 * gra))
        out.add("פלג המנחה" to f(rise + 10.75 * gra))
        out.add("שקיעה" to f(set))
        if (tzeit != null) out.add("צאת הכוכבים" to f(tzeit))
        out.add("רבנו תם (72 דק׳)" to f(set + 1.2))
        return out
    }
}
