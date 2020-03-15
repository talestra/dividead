#!/usr/bin/env kotlin

@file:Repository("https://jcenter.bintray.com/")
@file:Repository("https://dl.bintray.com/korlibs/korlibs/")
@file:DependsOn("com.soywiz.korlibs.korio:korio-jvm:1.10.0")
@file:DependsOn("com.soywiz.korlibs.korim:korim-jvm:1.10.0")
@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202")
@file:DependsOn("log4j:log4j:1.2.14")
//@file:CompilerOptions()
//@file:Import()

// https://github.com/Kotlin/KEEP/blob/master/proposals/scripting-support.md#kotlin-main-kts
// To install kotlin:
//   curl -s https://get.sdkman.io | bash
//   source "$HOME/.sdkman/bin/sdkman-init.sh"
//   sdk install kotlin

import com.soywiz.kmem.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.stream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.docopt.*
import java.util.*
import kotlin.collections.Iterable
import kotlin.collections.contains
import kotlin.collections.map
import kotlin.collections.set
import kotlin.collections.sliceArray
import kotlin.collections.toList

val args2: Array<String> = args

val usage = """
Dividead utilities

Usage:
  dividead.kts -l <file.dl1>
  dividead.kts -x <file.dl1>

Options:
  -h --help     Show this screen.
"""

val docopt = Docopt(usage)
val params = docopt.parse(args2.toList())

runBlocking {
    when {
        params["-l"] == true -> {
            val file = params["<file.dl1>"]!!.toString()
            println("Listing file... $file")
            val pack = DL1(file.uniVfs)
            for (file in pack.listRecursive()) {
                println(file.path)
            }
        }
        params["-x"] == true -> {
            val file = params["<file.dl1>"]!!.toString()
            println("Extracting file... $file")
            val pack = DL1(file.uniVfs)
            val out = "$file.d".uniVfs.ensureParents().apply { mkdir() }.jail()
            //out.mkdir()
            for (file in pack.listRecursive()) {
                val outFile = out[file.path]
                if (!outFile.exists()) {
                    println("$outFile...extracting")
                    val content = file.readAll().lzDecompressIfRequired()
                    out[file.path].write(content)
                } else {
                    println("$outFile...exists")
                }
            }
        }
    }
}

class DL1 : Vfs() {
    private var entries = LinkedHashMap<String, AsyncStream>()

    companion object {
        suspend operator fun invoke(file: VfsFile): VfsFile {
            return file.open().let { DL1(it) }
        }

        suspend operator fun invoke(stream: AsyncStream): VfsFile {
            val dl1 = DL1()
            // Read header
            val header = stream.readBytesExact(0x10).openAsync()
            val magic = header.readStringz(8)
            val count = header.readU16LE()
            val offset = header.readS32LE()
            var pos = 0x10

            println("Loading entries from DL1 $count: $offset")

            if (magic != "DL1.0\u001A") invalidArg("Invalid DL1 file. Magic : '$magic'")
            //Log.trace(Std.format("DL1: {offset=$offset, count=$count}"));
            // Read entries
            stream.position = offset.toLong()
            val it = stream.readBytesExact(16 * count)
            val entriesByteArray = it.openSync()
            for (n in 0 until count) {
                val name: String = entriesByteArray.readStringz(12)
                val size: Int = entriesByteArray.readS32LE()
                dl1.entries[name.toUpperCase()] = stream.sliceWithSize(pos.toLong(), size.toLong())
                pos += size
            }
            return dl1.root
        }
    }

    override suspend fun list(path: String): ReceiveChannel<VfsFile> {
        return this.entries.map { this.file(it.key) }.toChannel()
    }

    override suspend fun stat(path: String): VfsStat {
        return try {
            createExistsStat(path, isDirectory = false, size = getEntry(path).size())
        } catch (t: Throwable) {
            createNonExistsStat(path)
        }
    }

    fun listFiles(): Iterable<String> {
        return this.entries.keys
    }

    private fun getEntry(name: String): AsyncStream {
        val name = name.toUpperCase().trimStart('/')
        if (name !in entries) throw FileNotFoundException("Can't find '$name'")
        return entries[name]!!
    }

    override suspend fun open(path: String, mode: VfsOpenMode): AsyncStream {
        return getEntry(path).duplicate()
    }
}

object LZ {
    fun isCompressed(data: ByteArray): Boolean {
        return data.openSync().readStringz(2) == "LZ"
    }

    fun decompressIfRequired(data: ByteArray): ByteArray = if (isCompressed(data)) decompress(data) else data

    fun decompress(data: ByteArray): ByteArray {
        val data = data.openSync()
        val magic = data.readStringz(2)
        var compressedSize = data.readS32LE()
        val uncompressedSize = data.readS32LE()
        if (magic != "LZ") throw InvalidOperationException("Invalid LZ stream")
        return _decode(data, uncompressedSize)
    }

    private fun _decode(input: SyncStream, uncompressedSize: Int): ByteArray {
        //return measure("decoding image") { _decodeFast(input, uncompressedSize) }
        return _decodeFast(input, uncompressedSize)
    }

    /*
    private fun _decodeGeneric(input:BinBytes, uncompressedSize:Int):ByteArray {
        var options = LzOptions()
        options.ringBufferSize = 0x1000
        options.startRingBufferPos = 0xFEE
        //options.setCountPositionBits(4, 12)
        options.compressedBit = 0
        options.countPositionBytesHighFirst = false
        options.positionCountExtractor = new DivideadPositionCountExtractor()
        return LzDecoder.decode(input, options, uncompressedSize)
    }
    */

    // @:noStack
    private fun _decodeFast(input: SyncStream, uncompressedSize: Int): ByteArray {
        val i = UByteArrayInt((input.base as MemorySyncStreamBase).data.data)
        var ip: Int = input.position.toInt()
        val il: Int = input.length.toInt()

        val o = ByteArray(uncompressedSize + 0x1000)
        var op = 0x1000
        val ringStart = 0xFEE

        while (ip < il) {
            var code = i[ip++] or 0x100

            while (code != 1) {
                // Uncompressed
                if ((code and 1) != 0) {
                    o[op++] = i[ip++].toByte()
                }
                // Compressed
                else {
                    if (ip >= il) break
                    val paramL = i[ip++]
                    val paramH = i[ip++]
                    val param = paramL or (paramH shl 8)
                    val ringOffset = extractPosition(param)
                    val ringLength = extractCount(param)
                    val convertedP2 = ((ringStart + op) and 0xFFF) - ringOffset
                    val convertedP = if (convertedP2 < 0) convertedP2 + 0x1000 else convertedP2
                    val outputReadOffset = op - convertedP
                    for (n in 0 until ringLength) o[op + n] = o[outputReadOffset + n]
                    op += ringLength
                }

                code = code ushr 1
            }
        }

        return o.sliceArray(0x1000 until (0x1000 + uncompressedSize))
    }

    private fun extractPosition(param: Int): Int {
        return (param and 0xFF) or ((param ushr 4) and 0xF00)
    }

    private fun extractCount(param: Int): Int {
        return ((param ushr 8) and 0xF) + 3
    }
}

fun ByteArray.lzDecompressIfRequired() = LZ.decompressIfRequired(this)
