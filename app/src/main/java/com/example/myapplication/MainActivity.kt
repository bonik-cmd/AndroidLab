package com.example.myapplication

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.nehuatl.llamacpp.LlamaHelper
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

/** ЛР5: кэш результатов LLM-диагностики */
@Entity(tableName = "diagnoses")
data class DiagnosisEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vehicleVin: String,
    val symptoms: String,
    val answer: String,
    val createdAt: Long
)

@Dao
interface VehicleDao {
    // Последний добавленный автомобиль считается активным
    @Query("SELECT * FROM vehicles ORDER BY rowid DESC LIMIT 1")
    fun observeActiveVehicle(): Flow<VehicleEntity?>

    @Query("SELECT * FROM vehicles ORDER BY rowid DESC LIMIT 1")
    suspend fun getActiveVehicle(): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE vin = :vin LIMIT 1")
    suspend fun getVehicleByVin(vin: String): VehicleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleEntity)

    @Query("UPDATE vehicles SET currentMileageKm = :km WHERE vin = :vin AND currentMileageKm < :km")
    suspend fun raiseMileage(vin: String, km: Int)

    @Query("SELECT * FROM service_history WHERE vehicleVin = :vin ORDER BY mileageKm DESC")
    fun observeHistory(vin: String): Flow<List<ServiceHistoryEntity>>

    @Query("SELECT * FROM service_history WHERE vehicleVin = :vin ORDER BY mileageKm DESC LIMIT :limit")
    suspend fun getRecentHistory(vin: String, limit: Int): List<ServiceHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryItem(item: ServiceHistoryEntity): Long

    // --- ЛР5: кэш диагностик ---
    @Query("SELECT * FROM diagnoses WHERE vehicleVin = :vin AND symptoms = :symptoms ORDER BY createdAt DESC LIMIT 1")
    suspend fun findDiagnosis(vin: String, symptoms: String): DiagnosisEntity?

    @Query("SELECT * FROM diagnoses ORDER BY createdAt DESC LIMIT 20")
    fun observeDiagnoses(): Flow<List<DiagnosisEntity>>

    @Insert
    suspend fun insertDiagnosis(item: DiagnosisEntity): Long
}

@Database(
    entities = [VehicleEntity::class, ServiceHistoryEntity::class, DiagnosisEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
}

/** Миграция вместо fallbackToDestructiveMigration — данные пользователя не теряются */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diagnoses` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`vehicleVin` TEXT NOT NULL, " +
                    "`symptoms` TEXT NOT NULL, " +
                    "`answer` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
        )
    }
}

// ==========================================
// 2. NoSQL: документное хранилище техкарт (JSON-файл)
// ==========================================

data class FactoryMaintenanceCard(
    val cardId: String,
    val brand: String,
    val intervalKm: Int,
    val serviceTitle: String,
    val consumables: String
)

class JsonCardStore(context: Context, private val gson: Gson) {
    private val file = File(context.filesDir, "tech_cards.json")

    private fun readAll(): List<FactoryMaintenanceCard> {
        if (!file.exists()) return emptyList()
        return try {
            gson.fromJson(file.readText(), Array<FactoryMaintenanceCard>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            Log.e("JsonCardStore", "read failed", e)
            emptyList()
        }
    }

    suspend fun saveCards(cards: List<FactoryMaintenanceCard>) = withContext(Dispatchers.IO) {
        file.writeText(gson.toJson(cards))
    }

    fun getCardsByBrand(brand: String): List<FactoryMaintenanceCard> =
        readAll().filter { it.brand.equals(brand, ignoreCase = true) }

    fun isEmpty(): Boolean = readAll().isEmpty()
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
    val isLoading: Boolean = true,
    val vehicle: VehicleEntity? = null,
    val upcomingServices: List<ServiceTask> = emptyList(),
    val serviceHistory: List<ServiceHistoryEntity> = emptyList(),
    val monthlyExpenses: List<CostCategory> = emptyList(),
    val errorMessage: String? = null
)

// ----- ЛР5: состояния LLM -----
sealed interface ModelState {
    object Checking : ModelState
    data class Unsupported(val reason: String) : ModelState
    object ModelMissing : ModelState
    object Loading : ModelState
    data class Downloading(val percent: Int) : ModelState
    object Ready : ModelState
    data class Error(val message: String) : ModelState
}

sealed interface GenerationState {
    object Idle : GenerationState
    data class Loading(val partial: String = "") : GenerationState
    data class Success(val text: String, val fromCache: Boolean) : GenerationState
    data class Error(val message: String) : GenerationState
}

sealed interface DiagnosisResult {
    data class Partial(val text: String) : DiagnosisResult
    data class Final(val text: String, val fromCache: Boolean) : DiagnosisResult
}

// ----- ЛР6: состояния голоса -----
sealed interface VoiceUiState {
    object Idle : VoiceUiState
    data class Listening(val partial: String = "") : VoiceUiState
    object Processing : VoiceUiState
    data class Error(val message: String) : VoiceUiState
}

data class DiagnosisUiState(
    val symptoms: String = "",
    val vehicle: VehicleEntity? = null,
    val modelState: ModelState = ModelState.Checking,
    val generation: GenerationState = GenerationState.Idle,
    val voice: VoiceUiState = VoiceUiState.Idle,
    val pastDiagnoses: List<DiagnosisEntity> = emptyList()
)

// ==========================================
// 5. REPOSITORY
// ==========================================

class AutoRepository(
    private val apiService: NhtsaApiService,
    private val db: AppDatabase,
    private val cardStore: JsonCardStore
) {
    private val dao get() = db.vehicleDao()

    fun observeVehicle(): Flow<VehicleEntity?> = dao.observeActiveVehicle()
    fun observeHistory(vin: String): Flow<List<ServiceHistoryEntity>> = dao.observeHistory(vin)
    fun observeDiagnoses(): Flow<List<DiagnosisEntity>> = dao.observeDiagnoses()

    suspend fun getActiveVehicle(): VehicleEntity? = dao.getActiveVehicle()
    suspend fun recentHistory(vin: String, limit: Int) = dao.getRecentHistory(vin, limit)

    suspend fun addServiceRecord(item: ServiceHistoryEntity) {
        dao.insertHistoryItem(item)
        // Если пробег в записи больше текущего — обновляем пробег автомобиля
        dao.raiseMileage(item.vehicleVin, item.mileageKm)
    }

    suspend fun findDiagnosis(vin: String, symptoms: String) = dao.findDiagnosis(vin, symptoms)
    suspend fun saveDiagnosis(item: DiagnosisEntity) {
        dao.insertDiagnosis(item)
    }

    /**
     * allowFallback = true используется только для стартового демо-автомобиля (офлайн-режим).
     * Для реально отсканированного VIN при пустом ответе API возвращается null (ошибка).
     */
    suspend fun decodeAndSaveVin(vin: String, allowFallback: Boolean = false): VehicleEntity? =
        withContext(Dispatchers.IO) {
            val existing = dao.getVehicleByVin(vin)
            val values: Map<String, String> = try {
                apiService.decodeVin(vin).Results.mapNotNull { r ->
                    val k = r.Variable
                    val v = r.Value?.trim()
                    if (k != null && !v.isNullOrBlank()) k to v else null
                }.toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AutoRepository", "decodeVin failed", e)
                emptyMap()
            }

            val make = values["Make"]
            if (make == null && !allowFallback) return@withContext null

            val vehicle = VehicleEntity(
                vin = vin,
                brand = make ?: "Toyota",
                model = values["Model"] ?: if (make == null) "Camry" else "—",
                year = values["Model Year"] ?: if (make == null) "2021" else "—",
                engine = values["Displacement (L)"]?.toDoubleOrNull()
                    ?.let { String.format(Locale.US, "%.1f L", it) }
                    ?: if (make == null) "2.5 L" else "—",
                // Не затираем пользовательские данные при повторном скане того же VIN
                licensePlate = existing?.licensePlate ?: "A 777 AA 777",
                currentMileageKm = existing?.currentMileageKm ?: 45000
            )
            dao.insertVehicle(vehicle)
            vehicle
        }

    suspend fun getMaintenanceCards(brand: String): List<FactoryMaintenanceCard> =
        withContext(Dispatchers.IO) { cardStore.getCardsByBrand(brand) }

    suspend fun seedNoSqlIfEmpty() = withContext(Dispatchers.IO) {
        if (cardStore.isEmpty()) {
            cardStore.saveCards(
                listOf(
                    FactoryMaintenanceCard(
                        "card_1", "Toyota", 10000,
                        "Замена масла в ДВС и масляного фильтра",
                        "Масло 0W-20 4.5л, Фильтр масляный, Прокладка пробки"
                    ),
                    FactoryMaintenanceCard(
                        "card_2", "Toyota", 20000,
                        "Замена салонного и воздушного фильтров",
                        "Фильтр салона угольный, Фильтр воздушный ДВС"
                    ),
                    FactoryMaintenanceCard(
                        "card_3", "Toyota", 40000,
                        "Замена тормозной жидкости и свечей",
                        "Тормозная жидкость DOT4 1л, Свечи зажигания 4шт"
                    )
                )
            )
        }
    }
}

// ==========================================
// 6. ЛР5: LLM (On-Device, kotlinllamacpp + Qwen2.5-0.5B GGUF)
// ==========================================

interface LlmRepository {
    val modelState: StateFlow<ModelState>

    /** Проверка совместимости + загрузка модели (идемпотентно) */
    suspend fun prepare()

    /** Скачивание модели с Hugging Face (одноразово). true — успех */
    suspend fun downloadModel(): Boolean

    /** Копирование GGUF-файла, выбранного пользователем, во внутреннее хранилище */
    suspend fun importModel(uri: Uri)

    /** Потоковая генерация: каждый элемент — очередной фрагмент текста */
    fun generate(prompt: String): Flow<String>

    fun release()
}

class LlmRepositoryImpl(private val context: Context) : LlmRepository {

    companion object {
        const val MODEL_FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        const val CONTEXT_LENGTH = 2048
        const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val MIN_RAM_BYTES = 2L * 1024 * 1024 * 1024
    }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Checking)
    override val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 1024)
    private val helperDelegate = lazy { LlamaHelper(context.contentResolver, scope, llmFlow) }
    private val helper by helperDelegate

    private val modelFile: File get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    /** Возвращает причину несовместимости или null, если устройство подходит */
    private fun checkCompatibility(): String? {
        if (!Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
            return "Нужен 64-битный процессор arm64-v8a"
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (info.totalMem < MIN_RAM_BYTES) {
            return "Недостаточно ОЗУ: нужно минимум 2 ГБ"
        }
        return null
    }

    override suspend fun prepare() {
        val current = _modelState.value
        if (current is ModelState.Loading || current is ModelState.Ready || current is ModelState.Downloading) return

        checkCompatibility()?.let {
            _modelState.value = ModelState.Unsupported(it)
            return
        }
        if (!modelFile.exists()) {
            _modelState.value = ModelState.ModelMissing
            return
        }

        if (!isValidGguf(modelFile)) {
            modelFile.delete()
            _modelState.value = ModelState.Error(
                "Выбранный файл не является GGUF-моделью (или скачан не полностью). " +
                        "Скачайте $MODEL_FILE_NAME (~400 МБ) и выберите его снова."
            )
            return
        }

        _modelState.value = ModelState.Loading
        _modelState.value = try {
            withTimeout(180_000) { withContext(Dispatchers.IO) { loadModel() } }
            ModelState.Ready
        } catch (e: TimeoutCancellationException) {
            ModelState.Error("Таймаут загрузки модели")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("LlmRepository", "load failed", e)
            ModelState.Error(e.message ?: "Не удалось загрузить модель")
        }
    }

    /** GGUF-файл начинается с магических байт "GGUF" и весит сотни мегабайт */
    private fun isValidGguf(f: File): Boolean = try {
        f.length() > 50L * 1024 * 1024 && f.inputStream().use { ins ->
            val b = ByteArray(4)
            ins.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF"
        }
    } catch (e: Exception) {
        false
    }

    private suspend fun loadModel() = suspendCancellableCoroutine<Unit> { cont ->
        // Если библиотека сообщит об ошибке событием — не ждём таймаут, а сразу падаем
        val watcher = scope.launch {
            llmFlow.collect { e ->
                if (e is LlamaHelper.LLMEvent.Error && cont.isActive) {
                    cont.resumeWithException(IllegalStateException(e.message))
                }
            }
        }
        cont.invokeOnCancellation { watcher.cancel() }
        try {
            helper.load(
                path = Uri.fromFile(modelFile).toString(),
                contextLength = CONTEXT_LENGTH
            ) { _ ->
                watcher.cancel()
                if (cont.isActive) cont.resume(Unit)
            }
        } catch (e: Throwable) {
            watcher.cancel()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    override suspend fun downloadModel(): Boolean = withContext(Dispatchers.IO) {
        val target = modelFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$MODEL_FILE_NAME.part")
        _modelState.value = ModelState.Downloading(0)
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("сервер вернул код ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("пустой ответ сервера")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) {
                                    last = p
                                    _modelState.value = ModelState.Downloading(p)
                                }
                            }
                        }
                    }
                }
            }
            if (!isValidGguf(tmp)) throw IllegalStateException("скачанный файл повреждён")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("не удалось сохранить файл")
            _modelState.value = ModelState.Checking
            true
        } catch (e: CancellationException) {
            tmp.delete()
            _modelState.value = ModelState.ModelMissing
            throw e
        } catch (e: Exception) {
            tmp.delete()
            _modelState.value = ModelState.Error("Не удалось скачать модель: ${e.message}")
            false
        }
    }

    override suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        val target = modelFile
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть выбранный файл")
        _modelState.value = ModelState.Checking
    }

    override fun generate(prompt: String): Flow<String> =
        llmFlow
            // predict запускается ПОСЛЕ подписки — события не потеряются
            .onSubscription { helper.predict(prompt) }
            .onEach { event ->
                if (event is LlamaHelper.LLMEvent.Error) {
                    throw IllegalStateException(event.message)
                }
            }
            .takeWhile { it !is LlamaHelper.LLMEvent.Done }
            .filterIsInstance<LlamaHelper.LLMEvent.Ongoing>()
            .map { it.word }
            .onCompletion { cause ->
                if (cause is CancellationException) runCatching { helper.stopPrediction() }
            }
            .flowOn(Dispatchers.IO)

    override fun release() {
        runCatching {
            if (helperDelegate.isInitialized()) {
                helper.stopPrediction()
                helper.release()
            }
        }
        scope.cancel()
    }
}

/** UseCase: формирование промпта → вызов LLM → постобработка → кэш в Room */
class DiagnoseVehicleUseCase(
    private val llm: LlmRepository,
    private val repository: AutoRepository
) {
    operator fun invoke(vehicle: VehicleEntity, rawSymptoms: String): Flow<DiagnosisResult> = flow {
        val symptoms = rawSymptoms.trim().replace(Regex("\\s+"), " ").take(500)
        require(symptoms.isNotBlank()) { "Опишите симптомы неисправности" }

        // 1. Кэш (работает даже если модель недоступна — graceful degradation)
        repository.findDiagnosis(vehicle.vin, symptoms.lowercase())?.let {
            emit(DiagnosisResult.Final(it.answer, fromCache = true))
            return@flow
        }

        // 2. Модель должна быть готова
        check(llm.modelState.value is ModelState.Ready) {
            "Модель недоступна. Ранее сохранённые диагностики можно посмотреть ниже."
        }

        // 3. Промпт + генерация
        val history = repository.recentHistory(vehicle.vin, 5)
        val prompt = buildPrompt(vehicle, history, symptoms)
        val sb = StringBuilder()
        llm.generate(prompt).collect { chunk ->
            sb.append(chunk)
            emit(DiagnosisResult.Partial(postProcess(sb.toString())))
        }

        // 4. Постобработка + сохранение
        val answer = postProcess(sb.toString())
        check(answer.isNotBlank()) { "Модель вернула пустой ответ" }
        repository.saveDiagnosis(
            DiagnosisEntity(
                vehicleVin = vehicle.vin,
                symptoms = symptoms.lowercase(),
                answer = answer,
                createdAt = System.currentTimeMillis()
            )
        )
        emit(DiagnosisResult.Final(answer, fromCache = false))
    }

    private fun buildPrompt(
        vehicle: VehicleEntity,
        history: List<ServiceHistoryEntity>,
        symptoms: String
    ): String {
        val historyText = if (history.isEmpty()) "нет данных"
        else history.joinToString("; ") { "${it.title} (${it.mileageKm} км)" }

        val user = """
            Автомобиль: ${vehicle.brand} ${vehicle.model} ${vehicle.year}, двигатель ${vehicle.engine}, пробег ${vehicle.currentMileageKm} км.
            Последние работы по ТО: $historyText.
            Симптомы: $symptoms.
            Назови 2-3 возможные причины и что проверить в первую очередь.
            Ответ: нумерованный список причин с приоритетом, кратко, на русском языке.
        """.trimIndent()

        // ChatML-шаблон Qwen2.5
        return "<|im_start|>system\nТы – опытный автомеханик. Отвечай только на русском языке, кратко и по делу.<|im_end|>\n" +
                "<|im_start|>user\n$user<|im_end|>\n" +
                "<|im_start|>assistant\n"
    }

    private fun postProcess(raw: String): String =
        raw.replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .replace("<|endoftext|>", "")
            .trim()
}

// ==========================================
// 7. ЛР6: ГОЛОСОВОЙ ИНТЕРФЕЙС (SpeechRecognizer + TextToSpeech)
// ==========================================

interface VoiceRepository {
    val state: StateFlow<VoiceUiState>
    val recognized: SharedFlow<String>
    fun startListening()
    fun stopListening()
    fun speak(text: String)
    fun stopSpeaking()
    fun onPermissionDenied()
    fun resetState()
    fun destroy()
}

class VoiceRepositoryImpl(private val context: Context) : VoiceRepository {

    private val preferOffline = false // true -> EXTRA_PREFER_OFFLINE (нужен скачанный офлайн-пакет языка)

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    override val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _recognized = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val recognized: SharedFlow<String> = _recognized.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsReady) {
                    while (pendingSpeech.isNotEmpty()) speakNow(pendingSpeech.removeFirst())
                } else {
                    _state.value = VoiceUiState.Error("Русский язык синтеза речи не поддерживается")
                }
            } else {
                _state.value = VoiceUiState.Error("Не удалось инициализировать синтез речи")
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            _state.value = VoiceUiState.Processing
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) _state.value = VoiceUiState.Listening(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                _state.value = VoiceUiState.Error("Речь не распознана, попробуйте ещё раз")
            } else {
                _state.value = VoiceUiState.Processing
                _recognized.tryEmit(text)
            }
        }

        override fun onError(error: Int) {
            _state.value = VoiceUiState.Error(mapError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun mapError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на запись звука (RECORD_AUDIO)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон или распознаватель заняты"
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Проблема с сетью для распознавания"
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Вы ничего не сказали"
        SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера распознавания"
        SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента распознавания"
        10 -> "Слишком много запросов распознавания"
        11 -> "Сервис распознавания отключился"
        12 -> "Выбранный язык не поддерживается распознавателем"
        13 -> "Языковой пакет недоступен (скачайте офлайн-язык в настройках)"
        else -> "Неизвестная ошибка распознавания ($code)"
    }

    override fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceUiState.Error("Распознавание речи недоступно (нет сервисов Google?)")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = VoiceUiState.Error("Нет разрешения на запись звука (RECORD_AUDIO)")
            return
        }
        stopSpeaking()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        _state.value = VoiceUiState.Listening()
        r.startListening(intent)
    }

    override fun stopListening() {
        recognizer?.stopListening()
        if (_state.value is VoiceUiState.Listening) _state.value = VoiceUiState.Idle
    }

    override fun speak(text: String) {
        val clean = text.replace(Regex("[*#_`>]"), "").trim()
        if (clean.isEmpty()) return
        if (!ttsReady) {
            pendingSpeech.addLast(clean)
            return
        }
        speakNow(clean)
    }

    // У TTS есть лимит длины одной фразы (~4000 символов) — режем по предложениям
    private fun speakNow(text: String) {
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        text.split(Regex("(?<=[.!?\\n])")).forEach { sentence ->
            if (sb.length + sentence.length > 3000) {
                chunks += sb.toString(); sb.clear()
            }
            sb.append(sentence)
        }
        if (sb.isNotBlank()) chunks += sb.toString()
        chunks.forEachIndexed { i, chunk ->
            tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, "diag_$i")
        }
    }

    override fun stopSpeaking() {
        tts?.stop()
    }

    override fun onPermissionDenied() {
        _state.value = VoiceUiState.Error("Нет разрешения на запись звука (RECORD_AUDIO)")
    }

    override fun resetState() {
        if (_state.value !is VoiceUiState.Error) _state.value = VoiceUiState.Idle
    }

    override fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

// ==========================================
// 8. KOIN DI MODULES
// ==========================================

val appModule = module {
    single { Gson() }

    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    single {
        Retrofit.Builder()
            .baseUrl("https://vpic.nhtsa.dot.gov/")
            .client(get())
            .addConverterFactory(GsonConverterFactory.create(get<Gson>()))
            .build()
            .create(NhtsaApiService::class.java)
    }

    single {
        Room.databaseBuilder(get<Context>(), AppDatabase::class.java, "auto_assistant.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    single { JsonCardStore(androidContext(), get()) }
    single { AutoRepository(get(), get(), get()) }

    // factory: тяжёлые ресурсы принадлежат ViewModel и освобождаются в onCleared()
    factory<LlmRepository> { LlmRepositoryImpl(androidContext()) }
    factory<VoiceRepository> { VoiceRepositoryImpl(androidContext()) }

    viewModel { AutoAssistantViewModel(get()) }
    viewModel {
        val llm = get<LlmRepository>()
        val repo = get<AutoRepository>()
        DiagnosisViewModel(llm, DiagnoseVehicleUseCase(llm, repo), get<VoiceRepository>(), repo)
    }
}

// ==========================================
// 9. VIEW MODELS
// ==========================================

class AutoAssistantViewModel(private val repository: AutoRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private data class Snapshot(
        val vehicle: VehicleEntity?,
        val history: List<ServiceHistoryEntity>,
        val cards: List<FactoryMaintenanceCard>
    )

    init {
        viewModelScope.launch {
            repository.seedNoSqlIfEmpty()
            if (repository.getActiveVehicle() == null) {
                repository.decodeAndSaveVin("4T1B11HK8MW123456", allowFallback = true)
            }
            observeData()
        }
    }

    // Room Flow → UI: любые изменения БД автоматически попадают в состояние (UDF)
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun observeData() {
        repository.observeVehicle()
            .flatMapLatest { vehicle ->
                if (vehicle == null) flowOf(Snapshot(null, emptyList(), emptyList()))
                else repository.observeHistory(vehicle.vin).map { history ->
                    Snapshot(vehicle, history, repository.getMaintenanceCards(vehicle.brand))
                }
            }
            .collect { snap -> _uiState.update { it.copy(isLoading = false).withSnapshot(snap) } }
    }

    private fun DashboardUiState.withSnapshot(s: Snapshot): DashboardUiState {
        val vehicle = s.vehicle
        val tasks = if (vehicle == null) emptyList() else s.cards.map { card ->
            val interval = card.intervalKm.coerceAtLeast(1)
            ServiceTask(
                id = card.cardId,
                title = card.serviceTitle,
                // ближайший пробег, кратный интервалу регламента
                dueMileageKm = (vehicle.currentMileageKm / interval + 1) * interval,
                consumables = card.consumables
            )
        }.sortedBy { it.dueMileageKm }

        val serviceCost = s.history.sumOf { it.cost }.takeIf { it > 0 } ?: 8000.0
        val expenses = listOf(
            CostCategory("Топливо", 12500.0, 0xFF4CAF50),
            CostCategory("Обслуживание", serviceCost, 0xFF2196F3),
            CostCategory("Мойка/Детейлинг", 2500.0, 0xFFFF9800),
            CostCategory("Парковка", 1200.0, 0xFF9C27B0)
        )
        return copy(
            vehicle = vehicle,
            upcomingServices = tasks,
            serviceHistory = s.history,
            monthlyExpenses = expenses
        )
    }

    fun addServiceRecord(title: String, date: String, mileageKm: Int, cost: Double) {
        val currentVin = _uiState.value.vehicle?.vin ?: return
        viewModelScope.launch {
            repository.addServiceRecord(
                ServiceHistoryEntity(
                    vehicleVin = currentVin,
                    title = title,
                    date = date,
                    mileageKm = mileageKm,
                    cost = cost
                )
            )
        }
    }

    fun onVinScanned(vin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val decoded = repository.decodeAndSaveVin(vin)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = if (decoded == null) "Не удалось декодировать VIN: $vin" else null
                )
            }
        }
    }
}

class DiagnosisViewModel(
    private val llm: LlmRepository,
    private val diagnose: DiagnoseVehicleUseCase,
    private val voice: VoiceRepository,
    private val repository: AutoRepository
) : ViewModel() {

    private data class Local(
        val symptoms: String = "",
        val generation: GenerationState = GenerationState.Idle
    )

    private val local = MutableStateFlow(Local())
    private var job: Job? = null

    val uiState: StateFlow<DiagnosisUiState> = combine(
        local,
        llm.modelState,
        voice.state,
        repository.observeDiagnoses(),
        repository.observeVehicle()
    ) { l, model, v, past, vehicle ->
        DiagnosisUiState(
            symptoms = l.symptoms,
            vehicle = vehicle,
            modelState = model,
            generation = l.generation,
            voice = v,
            pastDiagnoses = past
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosisUiState())

    init {
        // Голосовой результат → текст симптомов → диагностика → озвучивание (однонаправленный поток)
        viewModelScope.launch {
            voice.recognized.collect { text ->
                onSymptomsChanged(text)
                runDiagnosis(speakResult = true)
            }
        }
    }

    fun prepareModel() {
        viewModelScope.launch { llm.prepare() }
    }

    fun onDownloadModel() {
        viewModelScope.launch {
            if (llm.downloadModel()) llm.prepare()
        }
    }

    fun onModelPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                llm.importModel(uri)
                llm.prepare()
            } catch (e: Exception) {
                local.update { it.copy(generation = GenerationState.Error(e.message ?: "Ошибка импорта модели")) }
            }
        }
    }

    fun onSymptomsChanged(text: String) = local.update { it.copy(symptoms = text) }

    fun onDiagnoseClick() = runDiagnosis(speakResult = false)

    private fun runDiagnosis(speakResult: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            val vehicle = repository.getActiveVehicle()
            if (vehicle == null) {
                local.update { it.copy(generation = GenerationState.Error("Сначала добавьте автомобиль")) }
                voice.resetState()
                return@launch
            }
            local.update { it.copy(generation = GenerationState.Loading()) }
            diagnose(vehicle, local.value.symptoms)
                .catch { e ->
                    local.update {
                        it.copy(generation = GenerationState.Error(e.message ?: "Ошибка генерации"))
                    }
                }
                .collect { result ->
                    when (result) {
                        is DiagnosisResult.Partial ->
                            local.update { it.copy(generation = GenerationState.Loading(result.text)) }
                        is DiagnosisResult.Final -> {
                            local.update {
                                it.copy(generation = GenerationState.Success(result.text, result.fromCache))
                            }
                            if (speakResult) voice.speak(result.text)
                        }
                    }
                }
            voice.resetState()
        }
    }

    /** Нажатие на микрофон. Разрешение проверяет/запрашивает UI, сам SpeechRecognizer в UI не используется */
    fun onMicPressed(hasPermission: Boolean) {
        if (!hasPermission) {
            voice.onPermissionDenied()
            return
        }
        if (uiState.value.voice is VoiceUiState.Listening) voice.stopListening() else voice.startListening()
    }

    fun stopSpeaking() = voice.stopSpeaking()

    fun onScreenLeft() {
        voice.stopListening()
        voice.stopSpeaking()
    }

    override fun onCleared() {
        voice.destroy()   // SpeechRecognizer.destroy() + TextToSpeech.shutdown()
        llm.release()     // освобождение нативной модели
        super.onCleared()
    }
}

// ==========================================
// 10. MAIN ACTIVITY & UI
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(applicationContext) // не Activity — иначе утечка контекста
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
                onViewHistoryClick = { navController.navigate("service_history") },
                onDiagnosisClick = { navController.navigate("diagnosis") }
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
        composable("diagnosis") {
            // ViewModel привязана к этому экрану: при выходе вызовется onCleared() и модель выгрузится
            val diagnosisViewModel: DiagnosisViewModel = koinViewModel()
            DiagnosisScreen(
                viewModel = diagnosisViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun MainDashboardScreen(
    viewModel: AutoAssistantViewModel,
    onScanVinClick: () -> Unit,
    onViewHistoryClick: () -> Unit,
    onDiagnosisClick: () -> Unit
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

            state.errorMessage?.let { msg ->
                item { Text(text = msg, color = MaterialTheme.colorScheme.error) }
            }

            state.vehicle?.let { vehicle ->
                item { VehicleProfileCard(vehicle = vehicle) }
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
                    ) { Text(text = "📷 VIN-сканер") }
                    OutlinedButton(
                        onClick = onViewHistoryClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(text = "📋 История ТО") }
                }
            }

            item {
                FilledTonalButton(
                    onClick = onDiagnosisClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(text = "🔧 Автодиагностика (AI + голос)") }
            }

            item {
                Text(
                    text = "Заводские техкарты (JSON)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(state.upcomingServices) { service -> ServiceTaskCard(task = service) }

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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

            if (totalExpense > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(Color.LightGray, RoundedCornerShape(8.dp))
                ) {
                    expenses.forEach { category ->
                        val weight = (category.amount / totalExpense).toFloat().coerceAtLeast(0.01f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(weight)
                                .background(Color(category.colorHex))
                        )
                    }
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
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val detected = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Освобождаем ресурсы при уходе с экрана
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
            recognizer.close()
        }
    }

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

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (detected.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val match = Regex("[A-HJ-NPR-Z0-9]{17}").find(visionText.text.uppercase())
                                if (match != null && detected.compareAndSet(false, true)) {
                                    onVinDetected(match.value)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                    )
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

            Button(onClick = { showAddDialog = true }) { Text("+ Добавить") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.serviceHistory.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Записи о ремонтах отсутствуют в Room DB", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.serviceHistory, key = { it.id }) { item ->
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
                        value = titleText, onValueChange = { titleText = it },
                        label = { Text("Наименование работ") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = dateText, onValueChange = { dateText = it },
                        label = { Text("Дата (напр. 25.10.2024)") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = mileageText, onValueChange = { mileageText = it },
                        label = { Text("Пробег (км)") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = costText, onValueChange = { costText = it },
                        label = { Text("Стоимость (₽)") }, singleLine = true
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
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Отмена") }
            }
        )
    }
}

// ----- ЛР5 + ЛР6: экран автодиагностики -----

@Composable
fun DiagnosisScreen(
    viewModel: DiagnosisViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.prepareModel() }
    DisposableEffect(Unit) { onDispose { viewModel.onScreenLeft() } }

    // Разрешение запрашивается в UI, но SpeechRecognizer/TTS из Composable не вызываются
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onMicPressed(granted) }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onModelPicked(it) } }

    val onMicClick = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onMicPressed(true)
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val generating = state.generation is GenerationState.Loading

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
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
                    text = "Автодиагностика",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        state.vehicle?.let { v ->
            item {
                Text(
                    text = "${v.brand} ${v.model} ${v.year}, ${v.currentMileageKm} км",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Статус модели
        item {
            ModelStatusCard(
                modelState = state.modelState,
                onImportClick = { modelPicker.launch(arrayOf("*/*")) },
                onRetryClick = { viewModel.prepareModel() },
                onDownloadClick = viewModel::onDownloadModel
            )
        }

        // Ввод симптомов
        item {
            OutlinedTextField(
                value = state.symptoms,
                onValueChange = viewModel::onSymptomsChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Опишите симптомы (стук, вибрация, запах…)") },
                minLines = 3
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::onDiagnoseClick,
                    enabled = state.symptoms.isNotBlank() && !generating,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("🔧 Диагностировать") }

                val listening = state.voice is VoiceUiState.Listening
                OutlinedButton(
                    onClick = onMicClick,
                    enabled = !generating,
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (listening) "⏹ Стоп" else "🎤 Голос") }
            }
        }

        // Статус голоса
        item { VoiceStatus(state.voice) }

        // Результат
        item {
            when (val g = state.generation) {
                is GenerationState.Idle -> {}
                is GenerationState.Loading -> ResultCard(
                    title = "Генерация…",
                    text = g.partial,
                    showProgress = true
                )
                is GenerationState.Success -> {
                    ResultCard(
                        title = if (g.fromCache) "Ответ (из кэша)" else "Ответ модели",
                        text = g.text,
                        showProgress = false
                    )
                    TextButton(onClick = viewModel::stopSpeaking) { Text("🔇 Остановить озвучку") }
                }
                is GenerationState.Error -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = g.message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // История диагностик (кэш Room)
        if (state.pastDiagnoses.isNotEmpty()) {
            item {
                Text(
                    text = "Сохранённые диагностики",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(state.pastDiagnoses, key = { it.id }) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = d.symptoms, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = d.answer, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    modelState: ModelState,
    onImportClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (modelState) {
                ModelState.Checking -> Text("Проверка устройства и модели…")
                ModelState.Loading -> {
                    Text("Загрузка модели Qwen2.5-0.5B в память…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ModelState.Downloading -> {
                    Text("Скачивание модели: ${modelState.percent}% (~400 МБ, не закрывайте экран)")
                    LinearProgressIndicator(
                        progress = { modelState.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ModelState.Ready -> Text("✅ Модель готова (офлайн)")
                is ModelState.Unsupported -> Text(
                    "⚠️ Устройство не поддерживается: ${modelState.reason}.\n" +
                            "AI-диагностика недоступна, но кэш прошлых ответов работает."
                )
                ModelState.ModelMissing -> {
                    Text("Модель не найдена. Скачайте её (нужен интернет один раз, лучше Wi-Fi).")
                    Button(onClick = onDownloadClick) { Text("⬇️ Скачать модель (~400 МБ)") }
                    OutlinedButton(onClick = onImportClick) { Text("Выбрать файл вручную") }
                }
                is ModelState.Error -> {
                    Text("Ошибка модели: ${modelState.message}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownloadClick) { Text("Скачать заново") }
                        OutlinedButton(onClick = onRetryClick) { Text("Повторить") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImportClick) { Text("Выбрать файл") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceStatus(voice: VoiceUiState) {
    val (text, color) = when (voice) {
        VoiceUiState.Idle -> return
        is VoiceUiState.Listening ->
            (if (voice.partial.isBlank()) "🎤 Слушаю…" else "🎤 ${voice.partial}") to MaterialTheme.colorScheme.primary
        VoiceUiState.Processing -> "⏳ Обработка…" to MaterialTheme.colorScheme.primary
        is VoiceUiState.Error -> "⚠️ ${voice.message}" to MaterialTheme.colorScheme.error
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ResultCard(title: String, text: String, showProgress: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (showProgress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (text.isNotBlank()) Text(text = text)
        }
    }
}