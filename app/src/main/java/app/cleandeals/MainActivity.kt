package app.cleandeals

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.net.URL
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- Estado

data class UiState(
    val products: Map<String, Deal> = emptyMap(),
    val wishlist: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val searchResults: List<String> = emptyList(),
    val searching: Boolean = false,
    val searchMessage: String? = null,
    val dealIds: List<String> = emptyList(),
    val dealsLoading: Boolean = false,
    val dealsError: String? = null,
)

class DealsViewModel(app: Application) : AndroidViewModel(app) {
    // La lista de seguimiento se guarda solo en el teléfono.
    private val prefs = app.getSharedPreferences("wishlist", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(wishlist = prefs.getStringSet("ids", emptySet())?.toSet() ?: emptySet())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
        loadDeals()
    }

    /** Lee la página de ofertas de Microsoft y pide los precios de esos juegos. */
    fun loadDeals() {
        viewModelScope.launch {
            _state.update { it.copy(dealsLoading = true, dealsError = null) }
            try {
                val ids = Api.fetchDealIds()
                if (ids.isEmpty()) throw java.io.IOException("No se encontraron ofertas en la página de Microsoft")
                val fetched = Api.fetchProducts(ids)
                _state.update {
                    it.copy(
                        products = it.products + fetched.associateBy { d -> d.id },
                        dealIds = fetched.map { d -> d.id },
                        dealsLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(dealsLoading = false, dealsError = e.message ?: "No se pudieron cargar las ofertas") }
            }
        }
    }

    /** Actualiza los precios de los juegos que sigues. */
    fun load() {
        val ids = _state.value.wishlist
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val fetched = Api.fetchProducts(ids.toList()).associateBy { it.id }
                _state.update { it.copy(products = it.products + fetched, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "No se pudieron cargar los precios") }
            }
        }
    }

    /** Busca por nombre, o si el texto trae un enlace o ID de la tienda, usa ese juego directo. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchMessage = null, searchResults = emptyList()) }
            try {
                val ids = Api.extractId(q)?.let { listOf(it) } ?: Api.search(q)
                val fetched = if (ids.isEmpty()) emptyList() else Api.fetchProducts(ids)
                val order = ids.filter { id -> fetched.any { it.id == id } }
                _state.update {
                    it.copy(
                        products = it.products + fetched.associateBy { d -> d.id },
                        searchResults = order,
                        searching = false,
                        searchMessage = if (order.isEmpty()) {
                            "Sin resultados. Prueba con otro nombre o pega el enlace del juego en la tienda de Xbox."
                        } else {
                            null
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        searching = false,
                        searchMessage = (e.message ?: "Error de conexión") +
                            "\nSi la búsqueda por nombre falla, pega el enlace del juego de la tienda de Xbox.",
                    )
                }
            }
        }
    }

    fun toggleWish(id: String) {
        val next = _state.value.wishlist.toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.edit().putStringSet("ids", next).apply()
        _state.update { it.copy(wishlist = next) }
    }
}

// ---------------------------------------------------------------- Actividad

class MainActivity : ComponentActivity() {
    private val vm: DealsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CleanTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }
}

@Composable
fun CleanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------- Pantallas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: DealsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyDeals by rememberSaveable { mutableStateOf(false) }
    var dealQuery by rememberSaveable { mutableStateOf("") }
    var minDiscount by rememberSaveable { mutableIntStateOf(0) }
    var byPrice by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            0 -> "Ofertas Xbox · México"
                            1 -> "Mi lista"
                            else -> "Buscar juegos"
                        },
                    )
                },
                actions = {
                    if (tab != 2) {
                        IconButton(onClick = { if (tab == 0) vm.loadDeals() else vm.load() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    label = { Text("Ofertas") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                    label = { Text("Mi lista") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Buscar") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (s.loading || s.searching || s.dealsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (tab == 0) {
                val deals = s.dealIds.mapNotNull { s.products[it] }
                    .filter { it.discount > 0 && it.discount >= minDiscount }
                    .filter { dealQuery.isBlank() || it.title.contains(dealQuery.trim(), ignoreCase = true) }
                    .let { l -> if (byPrice) l.sortedBy { it.price } else l.sortedByDescending { it.discount } }

                OutlinedTextField(
                    value = dealQuery,
                    onValueChange = { dealQuery = it },
                    singleLine = true,
                    label = { Text("Filtrar por nombre") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0 to "Todas", 25 to "25%+", 50 to "50%+", 75 to "75%+").forEach { (v, label) ->
                        FilterChip(
                            selected = minDiscount == v,
                            onClick = { minDiscount = v },
                            label = { Text(label) },
                        )
                    }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !byPrice,
                        onClick = { byPrice = false },
                        label = { Text("Mayor descuento") },
                    )
                    FilterChip(
                        selected = byPrice,
                        onClick = { byPrice = true },
                        label = { Text("Menor precio") },
                    )
                }
                when {
                    s.dealsError != null && s.dealIds.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.dealsError ?: "", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.loadDeals() }) { Text("Reintentar") }
                    }

                    deals.isEmpty() && !s.dealsLoading -> Message("No hay ofertas con esos filtros.")

                    else -> DealList(deals, s.wishlist, vm, ctx)
                }
            } else if (tab == 1) {
                val list = s.wishlist.mapNotNull { s.products[it] }
                    .filter { !onlyDeals || it.discount > 0 }
                    .sortedWith(compareByDescending<Deal> { it.discount }.thenBy { it.title })

                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilterChip(
                        selected = onlyDeals,
                        onClick = { onlyDeals = !onlyDeals },
                        label = { Text("Solo en oferta") },
                    )
                }
                when {
                    s.wishlist.isEmpty() -> Message(
                        "Aún no sigues ningún juego. Toca el corazón en Ofertas o Buscar para guardarlos aquí.",
                    )

                    s.error != null && list.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.error ?: "", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.load() }) { Text("Reintentar") }
                    }

                    list.isEmpty() && !s.loading -> Message(
                        if (onlyDeals) "Ninguno de tus juegos está en oferta ahora mismo." else "Cargando…",
                    )

                    else -> DealList(list, s.wishlist, vm, ctx)
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Nombre del juego o enlace de la tienda") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                    trailingIcon = {
                        IconButton(onClick = { vm.search(query) }) {
                            Icon(Icons.Default.Search, contentDescription = "Buscar")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val results = s.searchResults.mapNotNull { s.products[it] }
                when {
                    s.searchMessage != null -> Message(s.searchMessage ?: "")
                    results.isEmpty() && !s.searching -> Message(
                        "Escribe el nombre de un juego, o pega el enlace de su página en xbox.com.",
                    )

                    else -> DealList(results, s.wishlist, vm, ctx)
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DealList(list: List<Deal>, wishlist: Set<String>, vm: DealsViewModel, ctx: Context) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { it.id }) { d ->
            DealCard(
                d = d,
                wished = d.id in wishlist,
                onToggle = { vm.toggleWish(d.id) },
                onOpen = { openStore(ctx, d.id) },
            )
        }
    }
}

@Composable
fun DealCard(d: Deal, wished: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                d.imageUrl,
                Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.discount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                "-${d.discount}%",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            money(d.msrp, d.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (d.price <= 0.0) "Gratis" else money(d.price, d.currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Seguir juego",
                    tint = if (wished) Color(0xFFE53935) else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Utilidades

private fun money(amount: Double, currencyCode: String): String {
    val f = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    runCatching { f.currency = Currency.getInstance(currencyCode) }
    return f.format(amount)
}

private fun openStore(ctx: Context, id: String) {
    val uri = Uri.parse("https://www.xbox.com/es-MX/games/store/-/$id")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Sin navegador instalado: no hay nada más que hacer.
    }
}

/** Cargador de imágenes mínimo: sin librerías externas, con caché en memoria. */
private object ImageCache {
    val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = url?.let { ImageCache.cache.get(it) }, url) {
        if (url != null && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.also { ImageCache.cache.put(url, it) }
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
