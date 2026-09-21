package com.example.myapplication

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.Executors

// ==========================================
// 1. DATA MODELS
// ==========================================

data class VehicleProfile(
    val brand: String,
    val model: String,
    val year: String = "",
    val engine: String = "",
    val vin: String,
    val licensePlate: String,
    val currentMileageKm: Int
)

data class ServiceTask(
    val id: String,
    val title: String,
    val dueMileageKm: Int,
    val dueDate: String,
    val isCompleted: Boolean = false
)

data class ServiceHistoryItem(
    val id: String,
    val title: String,
    val date: String,
    val mileageKm: Int,
    val cost: Double
)

data class CostCategory(
    val categoryName: String,
    val amount: Double,
    val colorHex: Long
)

data class DashboardUiState(
    val isLoading: Boolean = false,
    val vehicle: VehicleProfile? = null,
    val upcomingServices: List<ServiceTask> = emptyList(),
    val serviceHistory: List<ServiceHistoryItem> = emptyList(),
    val monthlyExpenses: List<CostCategory> = emptyList(),
    val errorMessage: String? = null
)

// ==========================================
// 2. RETROFIT API (NHTSA API)
// ==========================================

data class NhtsaResponse(
    val Results: List<NhtsaResult>
)

data class NhtsaResult(
    val Variable: String?,
    val Value: String?
)

interface NhtsaApiService {
    @GET("api/vehicles/decodevin/{vin}?format=json")
    suspend fun decodeVin(@Path("vin") vin: String): NhtsaResponse
}

class VehicleRepository(private val apiService: NhtsaApiService) {
    suspend fun decodeVin(vin: String): VehicleProfile? {
        return try {
            val response = apiService.decodeVin(vin)
            val results = response.Results

            val make = results.firstOrNull { it.Variable == "Make" }?.Value ?: "Unknown"
            val model = results.firstOrNull { it.Variable == "Model" }?.Value ?: "Unknown"
            val year = results.firstOrNull { it.Variable == "Model Year" }?.Value ?: ""
            val engine = results.firstOrNull { it.Variable == "Displacement (L)" }?.Value ?: ""

            VehicleProfile(
                brand = make,
                model = model,
                year = year,
                engine = if (engine.isNotBlank()) "$engine L" else "2.5L",
                vin = vin,
                licensePlate = "A 123 BC 777",
                currentMileageKm = 45200
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

// ==========================================
// 3. KOIN DI MODULES
// ==========================================

val appModule = module {
    single {
        Retrofit.Builder()
            .baseUrl("https://vpic.nhtsa.dot.gov/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NhtsaApiService::class.java)
    }
    single { VehicleRepository(get()) }
    viewModel { AutoAssistantViewModel(get()) }
}

// ==========================================
// 4. VIEW MODEL
// ==========================================

class AutoAssistantViewModel(private val repository: VehicleRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadMockData()
    }

    private fun loadMockData() {
        _uiState.update {
            DashboardUiState(
                isLoading = false,
                vehicle = VehicleProfile(
                    brand = "Toyota",
                    model = "Camry",
                    year = "2021",
                    engine = "2.5L",
                    vin = "4T1B11HK8MW123456",
                    licensePlate = "A 123 BC 777",
                    currentMileageKm = 45200
                ),
                upcomingServices = listOf(
                    ServiceTask("1", "Замена масла в ДВС и фильтров", 50000, "15.11.2026"),
                    ServiceTask("2", "Замена передних тормозных колодок", 52000, "01.12.2026"),
                    ServiceTask("3", "Диагностика ходовой части", 55000, "20.01.2027")
                ),
                serviceHistory = listOf(
                    ServiceHistoryItem("h1", "Замена свечей зажигания", "12.05.2026", 40000, 4500.0),
                    ServiceHistoryItem("h2", "Замена тормозной жидкости", "10.01.2026", 35000, 3200.0),
                    ServiceHistoryItem("h3", "ТО-3 (Масло + фильтры)", "15.08.2025", 30000, 9500.0)
                ),
                monthlyExpenses = listOf(
                    CostCategory("Топливо", 12500.0, 0xFF4CAF50),
                    CostCategory("Обслуживание", 8000.0, 0xFF2196F3),
                    CostCategory("Мойка/Детейлинг", 2500.0, 0xFFFF9800),
                    CostCategory("Парковка", 1200.0, 0xFF9C27B0)
                )
            )
        }
    }

    fun onVinScanned(vin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val decodedVehicle = repository.decodeVin(vin)

            if (decodedVehicle != null) {
                // Автоматическая генерация плана замен (Техкарта)
                val generatedTasks = listOf(
                    ServiceTask("gen_1", "Базовое ТО (${decodedVehicle.brand})", decodedVehicle.currentMileageKm + 8000, "10.12.2026"),
                    ServiceTask("gen_2", "Проверка свечей (${decodedVehicle.engine})", decodedVehicle.currentMileageKm + 15000, "15.03.2027")
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        vehicle = decodedVehicle,
                        upcomingServices = generatedTasks,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Не удалось декодировать VIN")
                }
            }
        }
    }
}

// ==========================================
// 5. MAIN ACTIVITY & NAVIGATION
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация Koin
        startKoin {
            modules(appModule)
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

// ==========================================
// 6. DASHBOARD SCREEN
// ==========================================

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
                    text = "Предстоящее обслуживание",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(state.upcomingServices) { service ->
                ServiceTaskCard(
                    task = service,
                    currentMileage = state.vehicle?.currentMileageKm ?: 0
                )
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

// ==========================================
// 7. VIN SCANNER SCREEN (ML KIT + CAMERAX)
// ==========================================

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

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CameraPreviewScanner(onVinDetected: (String) -> Unit) {
    val context = LocalContext.current
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

                    @OptIn(ExperimentalGetImage::class)
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

// ==========================================
// 8. SERVICE HISTORY SCREEN
// ==========================================

@Composable
fun ServiceHistoryScreen(
    viewModel: AutoAssistantViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Журнал ТО",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

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

// ==========================================
// 9. REUSABLE UI COMPONENTS
// ==========================================

@Composable
fun VehicleProfileCard(vehicle: VehicleProfile) {
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
                Text(text = "Текущий пробег:", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${vehicle.currentMileageKm} км",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ServiceTaskCard(task: ServiceTask, currentMileage: Int) {
    val kmLeft = task.dueMileageKm - currentMileage

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(text = "Срок до: ${task.dueDate}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "через $kmLeft км",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
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