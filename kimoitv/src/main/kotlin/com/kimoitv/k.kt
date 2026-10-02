package com.kimoitv

import com.lagradost.cloudstream3.TvType

object K {

    const val NAME = "KimoiTV"
    const val TAG = "kimoitv"
    const val LANG = "en"
    const val BASE_URL = "https://kimoitv.com"
    const val SLASH = "/"
    const val EMPTY = ""
    const val ENCODING = "UTF-8"

    const val PATH_TIMELINE = "/timeline/"
    const val PATH_LIST = "/list/"
    const val PATH_BROWSE = "/browse/"
    const val PATH_SEARCH = "/search/"
    const val PATH_TITLE = "/title/"
    const val PATH_DOWNLOAD = "/download/"

    const val ENDPOINT_STREAM = "/streamvpaid.php"

    const val PARAM_PAGE = "page"
    const val PARAM_SORT = "sort"
    const val PARAM_QUERY = "q"
    const val PARAM_SOURCE = "d"
    const val PARAM_FILE = "id"
    const val PARAM_SEPARATOR = "&"

    const val SORT_UPDATE = ""
    const val SORT_NEWEST = "newest"

    const val FIRST_PAGE = 1
    const val MIN_QUERY_LENGTH = 3
    const val QUICK_SEARCH_LIMIT = 20
    const val EPISODE_FALLBACK = 1
    const val SEASON_FALLBACK = 1
    const val SERVER_FALLBACK = 1
    const val MAX_EPISODE_PAGES = 60
    const val MAX_SERVERS = 24
    const val PREFIX_EPISODE = "E"
    const val SUFFIX_P = "p"

    const val TIMEOUT_MAIN_PAGE = 30_000L
    const val TIMEOUT_SEARCH = 30_000L
    const val TIMEOUT_QUICK_SEARCH = 15_000L
    const val TIMEOUT_LOAD = 90_000L
    const val TIMEOUT_LOAD_LINKS = 60_000L

    const val CACHE_SESSION = 600_000
    const val CACHE_CATALOG = 900_000
    const val CACHE_SEARCH = 600_000
    const val CACHE_DETAIL = 1_800_000
    const val CACHE_EPISODES = 600_000
    const val CACHE_NONE = 0

    const val PARALLEL_SEASONS = 4
    const val PARALLEL_SEVER_PAGES = 5
    const val PARALLEL_SERVERS = 5

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    const val ACCEPT_ANY = "*/*"
    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
    const val ACCEPT_MEDIA =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    const val ACCEPT_VIDEO =
        "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5"
    const val CONTENT_FORM = "application/x-www-form-urlencoded; charset=UTF-8"
    const val VALUE_XHR = "XMLHttpRequest"
    const val VALUE_SAME_ORIGIN = "same-origin"
    const val VALUE_NAVIGATE = "navigate"
    const val VALUE_DOCUMENT = "document"
    const val VALUE_UPGRADE = "1"
    const val SUB_WWW = "www."
    const val REFERER_ROOT = "$BASE_URL/"

    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_ACCEPT = "Accept"
    const val HEADER_ACCEPT_LANGUAGE = "Accept-Language"
    const val HEADER_ORIGIN = "Origin"
    const val HEADER_REFERER = "Referer"
    const val HEADER_REQUESTED_WITH = "X-Requested-With"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_UPGRADE = "Upgrade-Insecure-Requests"
    const val HEADER_SEC_FETCH = "Sec-Fetch-Site"
    const val HEADER_SEC_FETCH_MODE = "Sec-Fetch-Mode"
    const val HEADER_SEC_FETCH_DEST = "Sec-Fetch-Dest"
    const val HEADER_COOKIE = "Cookie"
    const val HEADER_SEC_CH_UA_MOBILE = "sec-ch-ua-mobile"
    const val HEADER_SEC_CH_UA_PLATFORM = "sec-ch-ua-platform"
    const val VALUE_MOBILE = "?1"
    const val VALUE_PLATFORM = "\"Android\""
    const val COOKIE_SEPARATOR = ";"
    const val COOKIE_JOIN = "; "
    const val COOKIE_CLEARANCE = "cf_clearance"
    const val COOKIE_CLEARANCE_PREFIX = "$COOKIE_CLEARANCE="

    const val HTTP_FORBIDDEN = 403
    const val HTTP_UNAVAILABLE = 503

    const val KEY_COOKIES = "cf_cookies"
    const val KEY_USER_AGENT = "cf_user_agent"
    const val PREFS = "kimoitv"
    const val UA_LOG = 24
    const val TITLE_LOG = 40
    const val PATH_BYPASS = PATH_TIMELINE
    const val POLL_INTERVAL_MS = 2_000L
    const val POLL_TIMEOUT_MS = 120_000L
    const val DISMISS_DELAY_MS = 1_500L
    const val WEBVIEW_HEIGHT_RATIO = 0.70
    const val STATUS_OK = "#4CAF50"
    const val STATUS_PENDING = "#A0A0B0"
    const val STATUS_DONE = "done"
    const val STATUS_LOADING = "loading challenge page"
    const val STATUS_CHALLENGE = "challenge active, solve the captcha above"
    const val STATUS_CHECKING = "page loaded, checking cookies"
    const val STATUS_TIMEOUT = "timed out, tap bypass again"
    const val LABEL_BYPASS = " cloudflare bypass"
    const val HINT_BYPASS = "solve the challenge below, this closes automatically"
    const val COLOR_BACKGROUND = "#1A1A2E"
    const val COLOR_HINT = "#707080"

    const val SEL_CARD =
        ".movie-grid > .content-card, ul.listview.image-listview > li.mounted, " +
            "ul.listview.image-listview.media > li, ul.listview.image-listview > li"
    const val SEL_CARD_TITLE_LINK = """a[href*="/title/"]"""
    const val SEL_CARD_LINK = "a.item"
    const val SEL_CARD_IMAGE = "img"
    const val SEL_CARD_TITLE = ".content-title"
    const val SEL_CARD_META = ".content-meta"
    const val SEL_CARD_LEGACY_TITLE = ".in > div > div"
    const val SEL_CARD_MUTED = ".text-muted"

    const val SEL_PAGINATION = "ul.pagination"
    const val SEL_PAGINATION_LINK = "a[href]"

    const val SEL_DETAIL_HEADER = ".card-header"
    const val SEL_DETAIL_POSTER = "#pilled img"
    const val SEL_OG_IMAGE = """meta[property="og:image"]"""
    const val SEL_DETAIL_PLOT = "#description > p.card-text"
    const val SEL_DETAIL_EXTRA = "#more > p"
    const val SEL_DETAIL_CAST = "#cast ul.listview > li > a.item"
    const val SEL_CAST_FIELD = "div > div"
    const val CAST_ROLE_INDEX = 2
    const val SEL_CATEGORY = """a[href*="/list/"]"""
    const val SEL_GENRE = """a[href*="/genre/"] .chip-label"""
    const val SEL_SEASON = """a[href*="/Season-"]"""
    const val SEL_DOWNLOAD = "a[href*=\"" + PATH_DOWNLOAD + "\"]"

    const val SEL_FILE_INFO = "#fileInfo[data-id][data-name]"
    const val SEL_DATA_NAME = "[data-name]"
    const val SEL_VERSION_OPTION = "select > option[value]"
    const val SEL_VERSION_INPUT = "input[name=" + PARAM_SOURCE + "]"

    const val SEL_SOURCE = "source"
    const val SEL_TRACK = "track"

    const val ATTR_HREF = "href"
    const val ATTR_SRC = "src"
    const val ATTR_SRC_ABS = "abs:src"
    const val ATTR_ALT = "alt"
    const val ATTR_DATA_ID = "data-id"
    const val ATTR_DATA_NAME = "data-name"
    const val ATTR_VALUE = "value"
    const val ATTR_SRCLANG = "srclang"
    const val ATTR_LABEL = "label"
    const val ATTR_TYPE = "type"
    const val ATTR_CONTENT = "content"

    const val SUFFIX_M3U8 = ".m3u8"
    const val SUFFIX_MPD = ".mpd"
    const val SUFFIX_MP4 = ".mp4"
    const val SUFFIX_MKV = ".mkv"
    const val SUFFIX_WEBM = ".webm"
    const val SUFFIX_TS = ".ts"
    const val SUFFIX_TORRENT = ".torrent"
    const val SUFFIX_MAGNET = "magnet:"
    const val TOKEN_MPEG_URL = "mpegurl"
    const val TOKEN_DASH = "dash+xml"

    const val SUB_LANG_FALLBACK = "en"
    const val LABEL_VERSIONS_OPEN = " ("
    const val LABEL_VERSIONS_CLOSE = " versions)"
    const val TITLE_SUFFIX = " - KimoiTV"
    const val LABEL_RELEASE_DATE = "Release Date:"
    const val LABEL_RUNTIME = "Run time:"
    const val SEPARATOR_SPACE = " "
    const val SEPARATOR_DASH = " - "
    const val SEPARATOR_QUERY = "?"

    data class Catalog(val label: String, val path: String, val type: TvType, val sort: String)

    val CATALOGS = listOf(
        Catalog("Latest Updates", PATH_TIMELINE, TvType.TvSeries, SORT_UPDATE),
        Catalog("Hollywood", PATH_LIST + "Hollywood.html", TvType.Movie, SORT_UPDATE),
        Catalog("Animation", PATH_BROWSE + "Animation.html", TvType.Cartoon, SORT_NEWEST),
        Catalog("Korean Movies", PATH_LIST + "K-movie.html", TvType.Movie, SORT_UPDATE),
        Catalog("Korean Drama", PATH_LIST + "K-drama.html", TvType.TvSeries, SORT_UPDATE),
        Catalog("Chinese Movies", PATH_LIST + "C-movie.html", TvType.Movie, SORT_UPDATE),
        Catalog("Chinese Drama", PATH_LIST + "C-drama.html", TvType.TvSeries, SORT_UPDATE),
        Catalog("Japanese Drama", PATH_LIST + "J-drama.html", TvType.TvSeries, SORT_UPDATE),
    )

    val RE_PAGE = Regex("[?&]${PARAM_PAGE}=(\\d+)")
    val RE_WHITESPACE = Regex("\\s+")
    val RE_YEAR = Regex("\\b((?:19|20)\\d{2})\\b")
    val RE_SEASON = Regex("(?i)season[-_ ]?(\\d{1,3})")
    val RE_EPISODE_STD = Regex("(?i)[^a-z0-9]s(\\d{1,3})e(\\d{1,3})")
    val RE_EPISODE_WORD = Regex("(?i)episode[-_ ]?(\\d{1,4})")
    val RE_EPISODE_INDEX = Regex("(?i)e(?:pisode)?[-_ ]?(\\d{1,4})")
    val RE_RESOLUTION = Regex("(?i)(\\d{3,4})\\s*[pP]\\b|\\b(\\d{3,4})p\\b")
    val RE_RESOLUTION_TOKEN = Regex("(?i)[^a-z0-9](2160|1440|1080|720|480|360|240)[^a-z0-9]")
    val RE_DIGITS = Regex("\\d+")

    val TYPE_TOKENS = listOf(
        setOf("anime", "animation", "cartoon", "toon") to TvType.Anime,
        setOf("k drama", "kdrama", "korean drama", "k-drama") to TvType.TvSeries,
        setOf("j drama", "jdrama", "japanese drama", "j-drama") to TvType.TvSeries,
        setOf("c drama", "cdrama", "chinese drama", "c-drama") to TvType.TvSeries,
        setOf("tv series", "tvseries", "series", "web series", "tv show") to TvType.TvSeries,
        setOf("movie", "movies", "film") to TvType.Movie,
    )

    val CHALLENGE_TITLES = listOf(
        "just a moment",
        "checking your browser",
        "attention required",
        "one more step",
        "ddos-guard",
    )

    val CHALLENGE_PHRASES = listOf(
        "just a moment",
        "checking your browser",
        "attention required",
        "verify you are human",
        "ddos-guard",
        "cf-browser-verification",
        "challenge-platform",
        "cf_chl_opt",
    )
}