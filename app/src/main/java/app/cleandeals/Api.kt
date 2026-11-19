package app.cleandeals

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Deal(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val price: Double,
    val msrp: Double,
    val currency: String,
) {
    val discount: Int
        get() = if (msrp > 0 && price < msrp) Math.round((1 - price / msrp) * 100).toInt() else 0
}

/**
 * Toda la comunicación con la red vive en este archivo.
 * Solo se usa el catálogo público de Microsoft Store (displaycatalog.mp.microsoft.com):
 * precios por ID y búsqueda por nombre. No es una API oficial documentada, así que si
 * algún día cambia, este es el único archivo que hay que ajustar.
 */
object Api {
    const val MARKET = "MX"
    const val LANGUAGE = "es-MX"

    private const val BASE = "https://displaycatalog.mp.microsoft.com"

    private const val CATALOG =
        "$BASE/v7.0/products?market=$MARKET&languages=$LANGUAGE&MS-CV=DGU1mcuYo0WMMp%2BF.1"

    private const val SUGGEST =
        "$BASE/v7.0/productFamilies/autosuggest?market=$MARKET&languages=$LANGUAGE&mediaType=games"

    // Un ID de la tienda tiene 12 letras/números y al menos un número (p. ej. 9NBLGGH4R315).
    private val idRegex = Regex("\\b(?=[0-9A-Z]*\\d)[0-9A-Z]{12}\\b")

    /** Saca el ID de un enlace de la tienda de Xbox/Microsoft o de un ID pegado tal cual. */
    fun extractId(text: String): String? = idRegex.find(text.uppercase())?.value

    private const val DEALS_PAGE = "https://www.microsoft.com/es-mx/store/deals/apps/xbox"

    // Enlaces de producto de la página de ofertas: .../p/nombre-del-juego/9NBLGGH4R315
    private val productLink = Regex("/p/[^/\"'?#\\s]+/([0-9A-Za-z]{12})(?=[\"'?#/\\s<])")
    private val totalItems = Regex("\\d+\\s*-\\s*\\d+\\s+de\\s+(\\d+)")

    private fun httpGet(url: String, html: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            if (html) {
                conn.setRequestProperty("Accept", "text/html")
                conn.setRequestProperty("Accept-Language", "es-MX,es;q=0.9")
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0 Mobile Safari/537.36",
                )
            } else {
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "CleanDeals/1.0")
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("El servidor respondió con el código $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun idsFromHtml(html: String): List<String> =
        productLink.findAll(html).map { it.groupValues[1].uppercase() }.toList()

    /**
     * Lee la página pública de ofertas de Microsoft (50 juegos por página) y devuelve los IDs.
     * Los precios se piden después al catálogo con [fetchProducts].
     */
    suspend fun fetchDealIds(): List<String> = coroutineScope {
        val first = withContext(Dispatchers.IO) { httpGet(DEALS_PAGE, html = true) }
        val total = totalItems.find(first)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        val rest = (50 until minOf(total, 500) step 50).map { skip ->
            async(Dispatchers.IO) {
                runCatching { httpGet("$DEALS_PAGE?skipitems=$skip", html = true) }.getOrDefault("")
            }
        }.awaitAll()
        (listOf(first) + rest).flatMap { idsFromHtml(it) }.distinct()
    }

    /** Busca juegos por nombre y devuelve sus IDs (máximo 15). */
    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        val text = httpGet("$SUGGEST&query=" + java.net.URLEncoder.encode(query, "UTF-8"))
        val sets = JSONObject(text).optJSONArray("ResultSets") ?: JSONArray()
        val ids = LinkedHashSet<String>()
        for (i in 0 until sets.length()) {
            val suggests = sets.optJSONObject(i)?.optJSONArray("Suggests") ?: continue
            for (j in 0 until suggests.length()) {
                val metas = suggests.optJSONObject(j)?.optJSONArray("Metas") ?: continue
                for (k in 0 until metas.length()) {
                    val m = metas.optJSONObject(k) ?: continue
                    val key = m.optString("Key")
                    if (key == "BigCatalogId" || key == "ProductId") {
                        val v = m.optString("Value")
                        if (v.isNotEmpty()) ids.add(v)
                    }
                }
            }
        }
        ids.take(15)
    }

    private fun parseProducts(text: String): List<Deal> {
        val products = JSONObject(text).optJSONArray("Products") ?: return emptyList()
        val out = ArrayList<Deal>()
        for (i in 0 until products.length()) {
            val p = products.optJSONObject(i) ?: continue
            val id = p.optString("ProductId")
            if (id.isEmpty()) continue

            val loc = p.optJSONArray("LocalizedProperties")?.optJSONObject(0)
            val title = loc?.optString("ProductTitle").orEmpty()
            if (title.isEmpty()) continue

            var image: String? = null
            val images = loc?.optJSONArray("Images")
            if (images != null) {
                val byPurpose = HashMap<String, String>()
                for (j in 0 until images.length()) {
                    val im = images.optJSONObject(j) ?: continue
                    val purpose = im.optString("ImagePurpose")
                    val uri = im.optString("Uri")
                    if (uri.isNotEmpty() && purpose !in byPurpose) byPurpose[purpose] = uri
                }
                image = byPurpose["BoxArt"] ?: byPurpose["Poster"] ?: byPurpose["Tile"]
                    ?: byPurpose.values.firstOrNull()
                if (image != null) {
                    if (image.startsWith("//")) image = "https:$image"
                    if (image.startsWith("http://")) image = "https://" + image.removePrefix("http://")
                    image += if ('?' in image) "&w=300" else "?w=300"
                }
            }

            // Se busca la disponibilidad "Purchase" con precio.
            var price = -1.0
            var msrp = 0.0
            var currency = "MXN"
            val skus = p.optJSONArray("DisplaySkuAvailabilities")
            if (skus != null) {
                loop@ for (s in 0 until skus.length()) {
                    val avs = skus.optJSONObject(s)?.optJSONArray("Availabilities") ?: continue
                    for (a in 0 until avs.length()) {
                        val av = avs.optJSONObject(a) ?: continue
                        val actions = av.optJSONArray("Actions")
                        var purchasable = actions == null
                        if (actions != null) {
                            for (k in 0 until actions.length()) {
                                if (actions.optString(k) == "Purchase") purchasable = true
                            }
                        }
                        if (!purchasable) continue
                        val pr = av.optJSONObject("OrderManagementData")?.optJSONObject("Price") ?: continue
                        val list = pr.optDouble("ListPrice", -1.0)
                        if (list < 0) continue
                        price = list
                        msrp = pr.optDouble("MSRP", list)
                        currency = pr.optString("CurrencyCode").ifEmpty { "MXN" }
                        break@loop
                    }
                }
            }
            if (price < 0) continue
            if (msrp < price) msrp = price
            out.add(Deal(id, title, image, price, msrp, currency))
        }
        return out
    }

    /** Detalles y precios de una lista de IDs, pedidos en lotes en paralelo. */
    suspend fun fetchProducts(ids: List<String>): List<Deal> = coroutineScope {
        val results = ids.distinct().chunked(20).map { chunk ->
            async(Dispatchers.IO) {
                runCatching { parseProducts(httpGet("$CATALOG&bigIds=${chunk.joinToString(",")}")) }
            }
        }.awaitAll()
        val ok = results.filter { it.isSuccess }
        if (ok.isEmpty() && results.isNotEmpty()) throw results.first().exceptionOrNull()!!
        ok.flatMap { it.getOrDefault(emptyList()) }
    }
}
