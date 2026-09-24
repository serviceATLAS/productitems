package uk.co.smcontracts.atlas

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class StockItem(
    val sageCode: String,
    val productName: String
)

private data class UsedItem(
    val sageCode: String,
    val name: String,
    val quantity: Int
)

private val DEFAULT_STOCK_ITEMS = listOf(
    StockItem("", "RJ45 Connector"),
    StockItem("", "CAT6 Patch Lead"),
    StockItem("", "CAT6 Cable"),
    StockItem("", "HDMI Lead"),
    StockItem("", "USB Lead"),
    StockItem("", "12V PSU"),
    StockItem("", "24V PSU"),
    StockItem("", "Fuse"),
    StockItem("", "Relay"),
    StockItem("", "Batteries"),
    StockItem("", "IR Emitter"),
    StockItem("", "Fixings")
)

private fun usedItemsPrefs(context: Context) =
    context.getSharedPreferences("atlas_used_items", Context.MODE_PRIVATE)

private fun usedItemsKey(ticketId: Long) = "ticket_$ticketId"
private const val STOCK_CATALOGUE_KEY = "stock_catalogue_v1"

private fun loadUsedItems(context: Context, ticketId: Long): List<UsedItem> {
    val raw = usedItemsPrefs(context).getString(usedItemsKey(ticketId), "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("name").trim()
                val sageCode = item.optString("sageCode").trim()
                val quantity = item.optInt("quantity", 1).coerceAtLeast(1)
                if (name.isNotBlank()) add(UsedItem(sageCode, name, quantity))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveUsedItems(context: Context, ticketId: Long, items: List<UsedItem>) {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject()
                .put("sageCode", item.sageCode)
                .put("name", item.name)
                .put("quantity", item.quantity)
        )
    }
    usedItemsPrefs(context).edit().putString(usedItemsKey(ticketId), array.toString()).apply()
}

private fun loadStockCatalogue(context: Context): List<StockItem> {
    val raw = usedItemsPrefs(context).getString(STOCK_CATALOGUE_KEY, null) ?: return DEFAULT_STOCK_ITEMS
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val sageCode = item.optString("sageCode").trim()
                val productName = item.optString("productName").trim()
                if (productName.isNotBlank()) add(StockItem(sageCode, productName))
            }
        }.ifEmpty { DEFAULT_STOCK_ITEMS }
    } catch (_: Exception) {
        DEFAULT_STOCK_ITEMS
    }
}

private fun saveStockCatalogue(context: Context, items: List<StockItem>) {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject()
                .put("sageCode", item.sageCode)
                .put("productName", item.productName)
        )
    }
    usedItemsPrefs(context).edit().putString(STOCK_CATALOGUE_KEY, array.toString()).apply()
}

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                current.append('"')
                i++
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                values.add(current.toString().trim())
                current.clear()
            }
            else -> current.append(c)
        }
        i++
    }

    values.add(current.toString().trim())
    return values
}

private fun importStockCsv(context: Context, uri: Uri): Pair<Boolean, String> {
    return try {
        val lines = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: return false to "Could not read the selected file."

        if (lines.isEmpty()) return false to "The CSV file is empty."

        val firstRow = parseCsvLine(lines.first())
        val hasHeader = firstRow.any {
            it.equals("Sage Code", ignoreCase = true) ||
                it.equals("SageCode", ignoreCase = true) ||
                it.equals("Product Name", ignoreCase = true) ||
                it.equals("ProductName", ignoreCase = true)
        }

        val dataLines = if (hasHeader) lines.drop(1) else lines
        val items = dataLines.mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val columns = parseCsvLine(line)
            if (columns.size < 2) return@mapNotNull null

            val sageCode = columns[0].trim().trim('"')
            val productName = columns.drop(1).joinToString(",").trim().trim('"')

            if (sageCode.isBlank() || productName.isBlank()) null
            else StockItem(sageCode, productName)
        }.distinctBy { it.sageCode.lowercase() }

        if (items.isEmpty()) {
            false to "No valid items found. Use: Sage Code,Product Name"
        } else {
            saveStockCatalogue(context, items)
            true to "${items.size} stock items imported."
        }
    } catch (e: Exception) {
        false to "CSV import failed: ${e.message ?: "Unknown error"}"
    }
}

/** Report screens can call this to include the same basket in their PDF/report. */
fun usedItemsTextForReport(context: Context, ticketId: Long): String =
    loadUsedItems(context, ticketId).joinToString("\n") {
        if (it.sageCode.isBlank()) {
            "${it.quantity} x ${it.name}"
        } else {
            "${it.quantity} x ${it.sageCode} - ${it.name}"
        }
    }

@Composable
fun UsedItemsScreen(
    call: ServiceCallItem,
    username: String,
    secret: String,
    integrationCode: String,
    resourceId: String,
    environment: String,
    engineerName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var basket by remember(call.ticketId) {
        mutableStateOf(loadUsedItems(context, call.ticketId))
    }
    var stockItems by remember {
        mutableStateOf(loadStockCatalogue(context))
    }
    var searchText by remember { mutableStateOf("") }
    var manualText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun setBasket(newItems: List<UsedItem>) {
        basket = newItems
        saveUsedItems(context, call.ticketId, newItems)
    }

    fun addStockItem(stockItem: StockItem) {
        val existing = basket.indexOfFirst {
            if (stockItem.sageCode.isNotBlank()) {
                it.sageCode.equals(stockItem.sageCode, ignoreCase = true)
            } else {
                it.name.equals(stockItem.productName, ignoreCase = true)
            }
        }

        if (existing >= 0) {
            setBasket(
                basket.mapIndexed { index, item ->
                    if (index == existing) item.copy(quantity = item.quantity + 1) else item
                }
            )
        } else {
            setBasket(
                basket + UsedItem(
                    sageCode = stockItem.sageCode,
                    name = stockItem.productName,
                    quantity = 1
                )
            )
        }

        message = "${stockItem.productName} added"
    }

    fun addManualItem(rawName: String) {
        val name = rawName.trim()
        if (name.isBlank()) return

        val catalogueMatch = stockItems.firstOrNull {
            it.sageCode.equals(name, ignoreCase = true) ||
                it.productName.equals(name, ignoreCase = true)
        }

        if (catalogueMatch != null) {
            addStockItem(catalogueMatch)
            return
        }

        val existing = basket.indexOfFirst {
            it.sageCode.isBlank() && it.name.equals(name, ignoreCase = true)
        }

        if (existing >= 0) {
            setBasket(
                basket.mapIndexed { index, item ->
                    if (index == existing) item.copy(quantity = item.quantity + 1) else item
                }
            )
        } else {
            setBasket(basket + UsedItem("", name, 1))
        }

        message = "$name added"
    }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = importStockCsv(context, uri)
            message = result.second
            if (result.first) {
                stockItems = loadStockCatalogue(context)
                searchText = ""
            }
        }
    }

    val scanner = remember(context) { GmsBarcodeScanning.getClient(context) }

    val filteredItems = remember(stockItems, searchText) {
        val query = searchText.trim()
        if (query.isBlank()) {
            stockItems
        } else {
            stockItems.filter {
                it.productName.contains(query, ignoreCase = true) ||
                    it.sageCode.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Used Items",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(call.ticketTitle, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            label = { Text("Search items") },
            placeholder = { Text("Product name or Sage code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f).height(52.dp),
                onClick = {
                    message = "Opening QR scanner..."
                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val value = barcode.rawValue.orEmpty().trim()
                            if (value.isBlank()) {
                                message = "QR code contained no text."
                            } else {
                                val match = stockItems.firstOrNull {
                                    it.sageCode.equals(value, ignoreCase = true)
                                }
                                if (match != null) {
                                    addStockItem(match)
                                } else {
                                    message = "Sage code '$value' was not found in the stock list."
                                }
                            }
                        }
                        .addOnCanceledListener {
                            message = "QR scan cancelled."
                        }
                        .addOnFailureListener { error ->
                            message = "QR scanner error: ${error.message ?: "Unknown error"}"
                        }
                }
            ) {
                Text("Scan QR")
            }

            OutlinedButton(
                modifier = Modifier.weight(1f).height(52.dp),
                onClick = {
                    csvLauncher.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "text/plain",
                            "application/csv"
                        )
                    )
                }
            ) {
                Text("Import CSV")
            }
        }

        Text(
            "Stock Items",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (filteredItems.isEmpty()) {
            Text("No matching stock items.")
        } else {
            filteredItems.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { item ->
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .height(72.dp),
                            onClick = { addStockItem(item) }
                        ) {
                            Text(
                                text = item.productName,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }

                    if (rowItems.size == 1) {
                        Column(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it },
                label = { Text("Other item") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Button(
                modifier = Modifier.height(56.dp),
                onClick = {
                    addManualItem(manualText)
                    manualText = ""
                },
                enabled = manualText.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Text(
            "Items Used",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (basket.isEmpty()) {
            Text("No items selected yet.")
        } else {
            basket.forEachIndexed { index, item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(item.name, fontWeight = FontWeight.Bold)

                        if (item.sageCode.isNotBlank()) {
                            Text(
                                "Sage: ${item.sageCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f).height(48.dp),
                                onClick = {
                                    if (item.quantity <= 1) {
                                        setBasket(
                                            basket.filterIndexed { i, _ -> i != index }
                                        )
                                    } else {
                                        setBasket(
                                            basket.mapIndexed { i, x ->
                                                if (i == index) {
                                                    x.copy(quantity = x.quantity - 1)
                                                } else {
                                                    x
                                                }
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text("−")
                            }

                            Text(
                                text = item.quantity.toString(),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )

                            OutlinedButton(
                                modifier = Modifier.weight(1f).height(48.dp),
                                onClick = {
                                    setBasket(
                                        basket.mapIndexed { i, x ->
                                            if (i == index) {
                                                x.copy(quantity = x.quantity + 1)
                                            } else {
                                                x
                                            }
                                        }
                                    )
                                }
                            ) {
                                Text("+")
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1.5f).height(48.dp),
                                onClick = {
                                    setBasket(
                                        basket.filterIndexed { i, _ -> i != index }
                                    )
                                }
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFFE8F1FF),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            )
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = basket.isNotEmpty() && !busy,
            onClick = {
                busy = true
                message = "Adding items to Autotask..."

                scope.launch {
                    val itemLines = basket.joinToString("\n") {
                        if (it.sageCode.isBlank()) {
                            "${it.quantity} x ${it.name}"
                        } else {
                            "${it.quantity} x ${it.sageCode} - ${it.name}"
                        }
                    }

                    val addedBy = engineerName.ifBlank { "Resource $resourceId" }
                    val stamp = LocalDateTime.now(ZoneId.of("Europe/London"))
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

                    val result = addEngineerTicketNote(
                        apiUrl = getApiUrl(environment),
                        ticketId = call.ticketId,
                        resourceId = resourceId,
                        title = "Items Used",
                        description = "Items Used\n\n$itemLines\n\nAdded by: $addedBy\n$stamp",
                        username = username,
                        secret = secret,
                        integrationCode = integrationCode
                    )

                    busy = false
                    message = if (result.first) {
                        "Items added to the ticket. The list is saved for the report."
                    } else {
                        result.second
                    }
                }
            }
        ) {
            Text(if (busy) "Adding..." else "Add Items To Job")
        }

        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            onClick = onBack,
            enabled = !busy
        ) {
            Text("Back To Job")
        }
    }
}
