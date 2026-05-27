package com.example.autenticacioncontinua.ml.model

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteModelManager(private val context: Context) {

    private var interpreter: Interpreter? = null

    private var initializationError: String? = null

    init {
        try {
            val options = Interpreter.Options().apply {
                setUseNNAPI(false) // Compatible con minSdk 24
                addDelegate(FlexDelegate())
            }
            val modelBuffer = loadModelFile("startModel.tflite")
            interpreter = Interpreter(modelBuffer, options)
            interpreter?.allocateTensors()
            
            initializeModelVariables()
        } catch (e: Exception) {
            initializationError = "Error cargando modelo: ${e.message}. Asegúrate de colocar startModel.tflite en app/src/main/assets/"
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun getValidInterpreter(): Interpreter {
        if (initializationError != null) throw IllegalStateException(initializationError)
        return interpreter ?: throw IllegalStateException("Interpreter is closed")
    }

    fun getParameters(): List<ByteBuffer> {
        val interp = getValidInterpreter()
        
        val inputs = emptyMap<String, Any>()
        val outputs = mutableMapOf<String, Any>()
        
        // Get output tensor shapes from signature
        val outputTensors = interp.getSignatureOutputs("save")
        for (tensorName in outputTensors) {
            val tensor = interp.getOutputTensorFromSignature(tensorName, "save")
            val buffer = ByteBuffer.allocateDirect(tensor.numBytes())
            buffer.order(ByteOrder.nativeOrder())
            outputs[tensorName] = buffer
        }
        
        interp.runSignature(inputs, outputs, "save")
        
        // Sort keys to maintain consistent order, assuming tensor names can be sorted or ordered properly
        // In typical TFLite FL models, order is maintained by the array or we can just use values
        return outputs.toSortedMap().values.map { it as ByteBuffer }
    }

    fun setParameters(params: List<ByteBuffer>) {
        val interp = getValidInterpreter()
        
        // Assuming the TFLite model has a signature named "restore" to set weights
        val inputs = mutableMapOf<String, Any>()
        val outputs = emptyMap<String, Any>()
        
        val inputTensors = interp.getSignatureInputs("restore")
        val sortedNames = inputTensors.sorted()
        
        if (params.size != sortedNames.size) {
            throw IllegalArgumentException("Expected ${sortedNames.size} parameters, got ${params.size}")
        }
        
        for (i in params.indices) {
            inputs[sortedNames[i]] = params[i]
        }
        
        interp.runSignature(inputs, outputs, "restore")
    }

    fun runInference(window: FloatArray): Float {
        val interp = getValidInterpreter()
        
        // Input is [1, 128, 6]
        val inputArray = Array(1) { Array(128) { FloatArray(6) } }
        var k = 0
        for (i in 0 until 128) {
            for (j in 0 until 6) {
                inputArray[0][i][j] = window[k++]
            }
        }
        
        val inputs = mutableMapOf<String, Any>("x" to inputArray)
        val outputBuffer = ByteBuffer.allocateDirect(4) // 1 float output
        outputBuffer.order(ByteOrder.nativeOrder())
        val outputs = mutableMapOf<String, Any>("output" to outputBuffer)
        
        // Use "infer" signature
        interp.runSignature(inputs, outputs, "infer")
        
        outputBuffer.rewind()
        return outputBuffer.float
    }

    fun trainEpoch(positiveWindows: List<FloatArray>, negativeWindows: List<FloatArray>): Float {
        val interp = getValidInterpreter()
        
        var totalLoss = 0f
        
        // Combine and shuffle
        val dataset = mutableListOf<Pair<FloatArray, Float>>()
        positiveWindows.forEach { dataset.add(Pair(it, 1f)) }
        negativeWindows.forEach { dataset.add(Pair(it, 0f)) }
        dataset.shuffle()

        // Batch size = 1 for simplicity, typical in on-device training loops unless model supports batching natively
        for ((window, label) in dataset) {
            val inputArray = Array(1) { Array(128) { FloatArray(6) } }
            var k = 0
            for (i in 0 until 128) {
                for (j in 0 until 6) {
                    inputArray[0][i][j] = window[k++]
                }
            }
            
            val labelArray = floatArrayOf(label)
            
            val inputs = mutableMapOf<String, Any>(
                "x" to inputArray,
                "y" to labelArray
            )
            val lossBuffer = ByteBuffer.allocateDirect(4)
            lossBuffer.order(ByteOrder.nativeOrder())
            val outputs = mutableMapOf<String, Any>("loss" to lossBuffer)
            
            interp.runSignature(inputs, outputs, "train")
            
            lossBuffer.rewind()
            totalLoss += lossBuffer.float
        }
        
        return if (dataset.isEmpty()) 0f else totalLoss / dataset.size
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun initializeModelVariables() {
        val outputs = mutableMapOf<String, Any>(
            "status" to Array(1) { IntArray(1) }
        )
        interpreter?.runSignature(emptyMap<String, Any>(), outputs, "initialize")
    }
}
