package local.aiusagetrackers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import kotlinx.coroutines.*

@Service(Service.Level.APP)
class ConfigurationTransferService(private val scope: CoroutineScope) {
    internal var ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO
    fun <T> execute(work: () -> T, done: (Result<T>) -> Unit): Job {
        val modality = ModalityState.current()
        return scope.launch(ioDispatcher) {
            val result = try {
                Result.success(work())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            ensureActive()
            ApplicationManager.getApplication().invokeLater({ done(result) }, modality)
        }
    }
}
