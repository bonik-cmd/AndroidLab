package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.Executors

// ==========================================
// 1. ROOM DB (Реляционная БД)
// ==========================================

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val vin: String,
    val brand: String,
    val model: String,
    val year: String,
    val engine: String,
    val licensePlate: String,
    val currentMileageKm: Int
)

@Entity(tableName = "service_history")
data class ServiceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vehicleVin: String,
    val title: String,
    val date: String,
    val mileageKm: Int,
    val cost: Double
)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles LIMIT 1")
    suspend fun getActiveVehicle(): VehicleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleEntity)

    @Query("SELECT * FROM service_history WHERE vehicleVin = :vin ORDER BY mileageKm DESC")
    suspend fun getHistoryForVehicle(vin: String): List<ServiceHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryItem(item: ServiceHistoryEntity): Long
}

@Database(entities = [VehicleEntity::class, ServiceHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
}

// ==========================================
// 2. NoSQL DOCUMENT STORE (Заводские техкарты)
// ==========================================

data class FactoryMaintenanceCard(
    val cardId: String,
    val brand: String,
    val intervalKm: Int,
    val serviceTitle: String,
    val consumables: String
)

class NoSqlDocumentStore(context: Context, private val gson: Gson) {
    private val prefs = context.getSharedPreferences("nosql_tech_cards", Context.MODE_PRIVATE)

    fun saveCards(cards: List<FactoryMaintenanceCard>) {
        val json = gson.toJson(cards)
        prefs.edit().putString("tech_cards_json", json).apply()
    }

    fun getCardsByBrand(brand: String): List<FactoryMaintenanceCard> {
        val json = prefs.getString("tech_cards_json", null) ?: return emptyList()
        val type = object : TypeToken<List<FactoryMaintenanceCard>>() {}.type
        val allCards: List<FactoryMaintenanceCard> = gson.fromJson(json, type) ?: emptyList()
        return allCards.filter { it.brand.equals(brand, ignoreCase = true) }
    }

    fun isEmpty(): Boolean {
        return !prefs.contains("tech_cards_json")
    }
}

// ==========================================
// 3. RETROFIT API (NHTSA API)
// ==========================================

data class NhtsaResponse(val Results: List<NhtsaResult>)
data class NhtsaResult(val Variable: String?, val Value: String?)

interface NhtsaApiService {
    @GET("api/vehicles/decodevin/{vin}?format=json")
    suspend fun decodeVin(@Path("vin") vin: String): NhtsaResponse
}

// ==========================================
// 4. DATA MODELS & UI STATES
// ==========================================

data class ServiceTask(
    val id: String,
    val title: String,
    val dueMileageKm: Int,
    val consumables: String
)

data class CostCategory(
    val categoryName: String,
    val amount: Double,
    val colorHex: Long
)

data class DashboardUiState(
    val isLoading: Boolean = false,
    val vehicle: VehicleEntity? = null,
    val upcomingServices: List<ServiceTask> = emptyList(),
    val serviceHistory: List<ServiceHistoryEntity> = emptyList(),
    val monthlyExpenses: List<CostCategory> = emptyList(),
    val errorMessage: String? = null
)

// ==========================================
// 5. REPOSITORY
// ==========================================

class AutoRepository(
    private val apiService: NhtsaApiService,
    private val db: AppDatabase,
    private val noSqlStore: NoSqlDocumentStore
) {
    suspend fun getActiveVehicle(): VehicleEntity? = db.vehicleDao().getActiveVehicle()

    suspend fun getHistory(vin: String): List<ServiceHistoryEntity> = db.vehicleDao().getHistoryForVehicle(vin)

    suspend fun addServiceRecord(item: ServiceHistoryEntity) {
        db.vehicleDao().insertHistoryItem(item)
    }

    suspend fun decodeAndSaveVin(vin: String): VehicleEntity? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.decodeVin(vin)
            val results = response.Results

            val make = results.firstOrNull { it.Variable == "Make" }?.Value ?: "Toyota"
            val model = results.firstOrNull { it.Variable == "Model" }?.Value ?: "Camry"
            val year = results.firstOrNull { it.Variable == "Model Year" }?.Value ?: "2021"
            val engine = results.firstOrNull { it.Variable == "Displacement (L)" }?.Value ?: "2.5L"

            val vehicle = VehicleEntity(
                vin = vin,
                brand = make,
                model = model,
                year = year,
                engine = if (engine.endsWith("L")) engine else "$engine L",
                licensePlate = "A 777 AA 777",
                currentMileageKm = 45000
            )

            db.vehicleDao().insertVehicle(vehicle)
            vehicle
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getMaintenanceCards(brand: String): List<FactoryMaintenanceCard> {
        return noSqlStore.getCardsByBrand(brand)
    }

    suspend fun seedNoSqlIfEmpty() = withContext(Dispatchers.IO) {
        if (noSqlStore.isEmpty()) {
            val initialCards = listOf(
                FactoryMaintenanceCard(
                    cardId = "card_1",
                    brand = "Toyota",
                    intervalKm = 10000,
                    serviceTitle = "Замена масла в ДВС и масляного фильтра",
                    consumables = "Масло 0W-20 4.5л, Фильтр масляный, Прокладка пробки"
                ),
                FactoryMaintenanceCard(
                    cardId = "card_2",
                    brand = "Toyota",
                    intervalKm = 20000,
                    serviceTitle = "Замена салонного и воздушного фильтров",
                    consumables = "Фильтр салона угольный, Фильтр воздушный ДВС"
                ),
                FactoryMaintenanceCard(
                    cardId = "card_3",
                    brand = "Toyota",
                    intervalKm = 40000,
                    serviceTitle = "Замена тормозной жидкости и свечей",
                    consumables = "Тормозная жидкость DOT4 1л, Свечи зажигания 4шт"
                )
            )
            noSqlStore.saveCards(initialCards)
        }
    }
}

// ==========================================
// 6. KOIN DI MODULES
// ==========================================

val appModule = module {
    single { Gson() }

    single {
        Retrofit.Builder()
            .baseUrl("https://vpic.nhtsa.dot.gov/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NhtsaApiService::class.java)
    }

    single {
        Room.databaseBuilder(get<Context>(), AppDatabase::class.java, "auto_assistant.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    single { NoSqlDocumentStore(get(), get()) }

    single { AutoRepository(get(), get(), get()) }
    viewModel { AutoAssistantViewModel(get()) }
}

// ==========================================
// 7. VIEW MODEL
// ==========================================

class AutoAssistantViewModel(private val repository: AutoRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedNoSqlIfEmpty()
            loadData()
        }
    }

    private suspend fun loadData() {
        val vehicle = repository.getActiveVehicle() ?: repository.decodeAndSaveVin("4T1B11HK8MW123456")

        if (vehicle != null) {
            val history = repository.getHistory(vehicle.vin)
            val cards = repository.getMaintenanceCards(vehicle.brand)

            val upcomingTasks = cards.map { card ->
                ServiceTask(
                    id = card.cardId,
                    title = card.serviceTitle,
                    dueMileageKm = vehicle.currentMileageKm + card.intervalKm,
                    consumables = card.consumables
                )
            }

            val mockExpenses = listOf(
                CostCategory("Топливо", 12500.0, 0xFF4CAF50),
                CostCategory("Обслуживание", 8000.0, 0xFF2196F3),
                CostCategory("Мойка/Детейлинг", 2500.0, 0xFFFF9800),
                CostCategory("Парковка", 1200.0, 0xFF9C27B0)
            )

            _uiState.update {
                it.copy(
                    vehicle = vehicle,
                    upcomingServices = upcomingTasks,
                    serviceHistory = history,
                    monthlyExpenses = mockExpenses
                )
            }
        }
    }

    fun addServiceRecord(title: String, date: String, mileageKm: Int, cost: Double) {
        val currentVin = _uiState.value.vehicle?.vin ?: return

        viewModelScope.launch {
            val newRecord = ServiceHistoryEntity(
                vehicleVin = currentVin,
                title = title,
                date = date,
                mileageKm = mileageKm,
                cost = cost
            )
            repository.addServiceRecord(newRecord)
            loadData()
        }
    }

    fun onVinScanned(vin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val decodedVehicle = repository.decodeAndSaveVin(vin)

            if (decodedVehicle != null) {
                loadData()
                _uiState.update { it.copy(isLoading = false, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Не удалось декодировать VIN") }
            }
        }
    }
}

// ==========================================
// 8. MAIN ACTIVITY & UI
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@MainActivity)
                modules(appModule)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: AutoAssistantViewModel = koinViewModel()

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            MainDashboardScreen(
                viewModel = viewModel,
                onScanVinClick = { navController.navigate("vin_scanner") },
                onViewHistoryClick = { navController.navigate("service_history") }
            )
        }
        composable("vin_scanner") {
            VinScannerScreen(
                onVinDetected = { vin ->
                    viewModel.onVinScanned(vin)
                    navController.popBackStack()
                }
            )
        }
        composable("service_history") {
            ServiceHistoryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun MainDashboardScreen(
    viewModel: AutoAssistantViewModel,
    onScanVinClick: () -> Unit,
    onViewHistoryClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Помощник автолюбителя",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            state.vehicle?.let { vehicle ->
                item {
                    VehicleProfileCard(vehicle = vehicle)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onScanVinClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "📷 VIN-сканер")
                    }
                    OutlinedButton(
                        onClick = onViewHistoryClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "📋 История ТО")
                    }
                }
            }

            item {
                Text(
                    text = "Заводские техкарты (NoSQL Хранилище)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(state.upcomingServices) { service ->
                ServiceTaskCard(task = service)
            }

            item {
                Text(
                    text = "Затраты на содержание",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExpensesChartCard(expenses = state.monthlyExpenses)
            }
        }
    }
}

@Composable
fun VehicleProfileCard(vehicle: VehicleEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${vehicle.brand} ${vehicle.model} (${vehicle.year})",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Двигатель: ${vehicle.engine}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Гос. номер: ${vehicle.licensePlate}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "VIN: ${vehicle.vin}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Текущий пробег (Room DB):", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${vehicle.currentMileageKm} км",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ServiceTaskCard(task: ServiceTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "на ${task.dueMileageKm} км",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Расходники: ${task.consumables}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
        }
    }
}

@Composable
fun ExpensesChartCard(expenses: List<CostCategory>) {
    val totalExpense = expenses.sumOf { it.amount }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Итого за месяц: ${totalExpense.toInt()} ₽",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(Color.LightGray, RoundedCornerShape(8.dp))
            ) {
                expenses.forEach { category ->
                    val weight = (category.amount / totalExpense).toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(weight)
                            .background(Color(category.colorHex))
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            expenses.forEach { category ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(category.colorHex), RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = category.categoryName, fontSize = 14.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(text = "${category.amount.toInt()} ₽", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun VinScannerScreen(onVinDetected: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreviewScanner(onVinDetected = onVinDetected)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Для сканирования VIN нужен доступ к камере")
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreviewScanner(onVinDetected: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isProcessing by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    if (isProcessing) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val vinRegex = Regex("[A-HJ-NPR-Z0-9]{17}")
                                val match = vinRegex.find(visionText.text.uppercase())
                                if (match != null) {
                                    isProcessing = true
                                    onVinDetected(match.value)
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                } catch (e: Exception) {
                    Log.e("Camera", "Binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun ServiceHistoryScreen(
    viewModel: AutoAssistantViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    var titleText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var mileageText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "← Назад",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(8.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Журнал ТО",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(onClick = { showAddDialog = true }) {
                Text("+ Добавить")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.serviceHistory.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Записи о ремонтах отсутствуют в Room DB", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.serviceHistory) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = item.title, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Дата: ${item.date}", fontSize = 12.sp, color = Color.Gray)
                                Text(text = "${item.mileageKm} км", fontSize = 12.sp, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Стоимость: ${item.cost.toInt()} ₽",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Новая запись о ТО") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        label = { Text("Наименование работ") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Дата (напр. 25.10.2024)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = mileageText,
                        onValueChange = { mileageText = it },
                        label = { Text("Пробег (км)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Стоимость (₽)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mileage = mileageText.toIntOrNull() ?: 0
                        val cost = costText.toDoubleOrNull() ?: 0.0

                        if (titleText.isNotBlank()) {
                            viewModel.addServiceRecord(
                                title = titleText,
                                date = dateText.ifBlank { "Сегодня" },
                                mileageKm = mileage,
                                cost = cost
                            )
                            titleText = ""
                            dateText = ""
                            mileageText = ""
                            costText = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}