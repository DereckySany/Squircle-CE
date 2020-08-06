/*
 * Licensed to the Light Team Software (Light Team) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The Light Team licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightteam.localfilesystem.repository

import com.github.gzuliyujiang.chardet.CJKCharsetDetector
import com.lightteam.filesystem.exception.*
import com.lightteam.filesystem.model.*
import com.lightteam.filesystem.repository.Filesystem
import com.lightteam.filesystem.utils.endsWith
import com.lightteam.localfilesystem.BuildConfig
import com.lightteam.localfilesystem.converter.FileConverter
import com.lightteam.localfilesystem.utils.formatAsDate
import com.lightteam.localfilesystem.utils.formatAsSize
import com.lightteam.localfilesystem.utils.size
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import java.io.File
import java.io.IOException
import net.lingala.zip4j.ZipFile

class LocalFilesystem(private val defaultLocation: File) : Filesystem {

    override fun defaultLocation(): Single<FileTree> {
        return Single.create { emitter ->
            val parent = FileConverter.toModel(defaultLocation)
            if (defaultLocation.isDirectory) {
                val children = defaultLocation.listFiles()!!
                    .map(FileConverter::toModel)
                    .toList()
                val fileTree = FileTree(parent, children)
                emitter.onSuccess(fileTree)
            } else {
                emitter.onError(DirectoryExpectedException())
            }
        }
    }

    override fun provideDirectory(parent: FileModel?): Single<FileTree> {
        return if (parent != null) {
            Single.create { emitter ->
                val file = FileConverter.toFile(parent)
                if (file.isDirectory) {
                    val children = file.listFiles()!!
                        .map(FileConverter::toModel)
                        .toList()
                    val fileTree = FileTree(parent, children)
                    emitter.onSuccess(fileTree)
                } else {
                    emitter.onError(DirectoryExpectedException())
                }
            }
        } else {
            defaultLocation()
        }
    }

    override fun createFile(fileModel: FileModel): Single<FileModel> {
        return Single.create { emitter ->
            val file = FileConverter.toFile(fileModel)
            if (!file.exists()) {
                if (fileModel.isFolder) {
                    file.mkdirs()
                } else {
                    val parentFile = file.parentFile!!
                    if (!parentFile.exists()) {
                        parentFile.mkdirs()
                    }
                    file.createNewFile()
                }
                val fileModel2 = FileConverter.toModel(file)
                emitter.onSuccess(fileModel2)
            } else {
                emitter.onError(FileAlreadyExistsException(fileModel.path))
            }
        }
    }

    override fun renameFile(fileModel: FileModel, fileName: String): Single<FileModel> {
        return Single.create { emitter ->
            val originalFile = FileConverter.toFile(fileModel)
            val parentFile = originalFile.parentFile!!
            val renamedFile = File(parentFile, fileName)
            if (originalFile.exists()) {
                if (!renamedFile.exists()) {
                    originalFile.renameTo(renamedFile)
                    val renamedModel = FileConverter.toModel(renamedFile)
                    emitter.onSuccess(renamedModel)
                } else {
                    emitter.onError(FileAlreadyExistsException(renamedFile.absolutePath))
                }
            } else {
                emitter.onError(FileNotFoundException(fileModel.path))
            }
        }
    }

    override fun deleteFile(fileModel: FileModel): Single<FileModel> {
        return Single.create { emitter ->
            val file = FileConverter.toFile(fileModel)
            if (file.exists()) {
                file.deleteRecursively()
                val parentFile = FileConverter.toModel(file.parentFile!!)
                emitter.onSuccess(parentFile)
            } else {
                emitter.onError(FileNotFoundException(fileModel.path))
            }
        }
    }

    override fun copyFile(source: FileModel, dest: FileModel): Single<FileModel> {
        return Single.create { emitter ->
            val directory = FileConverter.toFile(dest)
            val sourceFile = FileConverter.toFile(source)
            val destFile = File(directory, sourceFile.name)
            if (sourceFile.exists()) {
                if (!destFile.exists()) {
                    sourceFile.copyRecursively(destFile, overwrite = false)
                    // val destFile2 = FileConverter.toModel(destFile)
                    // emitter.onSuccess(destFile2)
                    emitter.onSuccess(source)
                } else {
                    emitter.onError(FileAlreadyExistsException(dest.path))
                }
            } else {
                emitter.onError(FileNotFoundException(source.path))
            }
        }
    }

    override fun propertiesOf(fileModel: FileModel): Single<PropertiesModel> {
        return Single.create { emitter ->
            val file = File(fileModel.path)
            val fileType = fileModel.getType()
            if (file.exists()) {
                val result = PropertiesModel(
                    file.name,
                    file.absolutePath,
                    file.lastModified().formatAsDate(),
                    file.size().formatAsSize(),
                    getLineCount(file, fileType),
                    getWordCount(file, fileType),
                    getCharCount(file, fileType),
                    file.canRead(),
                    file.canWrite(),
                    file.canExecute()
                )
                emitter.onSuccess(result)
            } else {
                emitter.onError(FileNotFoundException(fileModel.path))
            }
        }
    }

    override fun compress(
        source: List<FileModel>,
        dest: FileModel,
        archiveName: String
    ): Observable<FileModel> {
        return Observable.create { emitter ->
            val directory = FileConverter.toFile(dest)
            val archiveFile = ZipFile(File(directory, archiveName))
            if (!archiveFile.file.exists()) {
                for (fileModel in source) {
                    val sourceFile = FileConverter.toFile(fileModel)
                    if (sourceFile.exists()) {
                        if (sourceFile.isDirectory) {
                            archiveFile.addFolder(sourceFile)
                        } else {
                            archiveFile.addFile(sourceFile)
                        }
                        emitter.onNext(fileModel)
                    } else {
                        emitter.onError(FileNotFoundException(fileModel.path))
                    }
                }
            } else {
                emitter.onError(FileAlreadyExistsException(archiveFile.file.absolutePath))
            }
            emitter.onComplete()
        }
    }

    override fun decompress(
        source: FileModel,
        dest: FileModel
    ): Single<FileModel> { // TODO: Use Observable
        return Single.create { emitter ->
            val sourceFile = FileConverter.toFile(source)
            when {
                !sourceFile.name.endsWith(arrayOf(".zip", ".zipx", ".jar")) -> {
                    emitter.onError(ArchiveUnsupportedException(source.path))
                }
                sourceFile.exists() -> {
                    val archiveFile = ZipFile(sourceFile)
                    when {
                        archiveFile.isEncrypted -> {
                            emitter.onError(ZipEncryptedException(source.path))
                        }
                        archiveFile.isSplitArchive -> {
                            emitter.onError(ZipSplitException(source.path))
                        }
                        archiveFile.isValidZipFile -> {
                            emitter.onError(ZipInvalidedException(source.path))
                        }
                        else -> {
                            archiveFile.extractAll(dest.path)
                            emitter.onSuccess(source)
                        }
                    }
                }
                else -> {
                    emitter.onError(FileNotFoundException(source.path))
                }
            }
        }
    }

    override fun loadFile(fileModel: FileModel, fileParams: FileParams): Single<String> {
        return Single.create { emitter ->
            val file = File(fileModel.path)
            if (file.exists()) {
                val charset = if (fileParams.chardet) {
                    CJKCharsetDetector.DEBUG = BuildConfig.DEBUG
                    file.inputStream().use {
                        CJKCharsetDetector.detect(it) ?: fileParams.charset
                    }
                } else {
                    fileParams.charset
                }
                try {
                    val text = file.readText(charset = charset)
                    emitter.onSuccess(text)
                } catch (e: OutOfMemoryError) {
                    emitter.onError(OutOfMemoryError(fileModel.path + " OOM"))
                }
            } else {
                emitter.onError(FileNotFoundException(fileModel.path))
            }
        }
    }

    override fun saveFile(fileModel: FileModel, text: String, fileParams: FileParams): Completable {
        return Completable.create { emitter ->
            try {
                val file = File(fileModel.path)
                if (!file.exists()) {
                    val parentFile = file.parentFile!!
                    if (!parentFile.exists()) {
                        parentFile.mkdirs()
                    }
                    file.createNewFile()
                }
                file.writeText(fileParams.linebreak(text), fileParams.charset)

                emitter.onComplete()
            } catch (e: IOException) {
                emitter.onError(e)
            }
        }
    }

    // region PROPERTIES

    private fun getLineCount(file: File, fileType: FileType): String {
        if (file.isFile && fileType == FileType.TEXT) {
            var lines = 0
            file.forEachLine {
                lines++
            }
            return lines.toString()
        }
        return "…"
    }

    private fun getWordCount(file: File, fileType: FileType): String {
        if (file.isFile && fileType == FileType.TEXT) {
            var words = 0
            file.forEachLine {
                words += it.split(' ').size
            }
            return words.toString()
        }
        return "…"
    }

    private fun getCharCount(file: File, fileType: FileType): String {
        if (file.isFile && fileType == FileType.TEXT) {
            return file.length().toString()
        }
        return "…"
    }

    // endregion PROPERTIES
}