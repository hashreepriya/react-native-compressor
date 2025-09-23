package com.reactnativecompressor.Audio

import android.content.Context
import android.media.*
import android.net.Uri
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.reactnativecompressor.Utils.MediaCache
import com.reactnativecompressor.Utils.Utils
import com.reactnativecompressor.Utils.Utils.addLog
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class AudioCompressor {

    companion object {
        private const val TAG = "AudioCompressor"

        @JvmStatic
        fun CompressAudio(
            fileUrl: String,
            optionMap: ReadableMap,
            context: ReactApplicationContext,
            promise: Promise
        ) {
            val realPath = Utils.getRealPath(fileUrl, context) ?: run {
                promise.reject("FILE_NOT_FOUND", "Invalid file path")
                return
            }

            val outputPath = Utils.generateCacheFilePath("m4a", context)

            try {
                compressAudioWithMediaCodec(realPath, outputPath, optionMap, context)
                promise.resolve("file://$outputPath")
            } catch (e: Exception) {
                e.printStackTrace()
                promise.reject("COMPRESSION_ERROR", e.localizedMessage)
            }
        }

        @Throws(IOException::class)
        private fun compressAudioWithMediaCodec(
            inputPath: String,
            outputPath: String,
            optionMap: ReadableMap,
            context: Context
        ) {
            addLog("$TAG: Starting AAC compression for $inputPath")

            val extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(inputPath), null)

            // Select the first audio track
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) throw IOException("No audio track found in $inputPath")
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = optionMap.getInt("samplerate").takeIf { it > 0 } ?: format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = optionMap.getInt("channels").takeIf { it > 0 } ?: format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bitrate = optionMap.getInt("bitrate").takeIf { it > 0 } ?: 128_000

            val mediaFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var outputTrackIndex = -1
            var isEOS = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!isEOS) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputBufferIndex >= 0) {
                    val encodedBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    if (bufferInfo.size != 0) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        if (outputTrackIndex == -1) {
                            val outFormat = codec.outputFormat
                            outputTrackIndex = muxer.addTrack(outFormat)
                            muxer.start()
                        }

                        muxer.writeSampleData(outputTrackIndex, encodedBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()
            extractor.release()

            addLog("$TAG: Compression completed, output saved at $outputPath")
        }

        private fun selectAudioTrack(extractor: MediaExtractor): Int {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return i
            }
            return -1
        }
    }
}
