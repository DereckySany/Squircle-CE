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

package com.lightteam.modpeide.data.utils.commons

import com.lightteam.filesystem.model.FileModel
import java.util.*
import kotlin.Comparator

object FileSorter {

    const val SORT_BY_NAME = 0
    const val SORT_BY_SIZE = 1
    const val SORT_BY_DATE = 2

    private val fileNameComparator: Comparator<in FileModel>
        get() = Comparator { first, second ->
            // Case insensitive
            first.name.toLowerCase(Locale.getDefault()).compareTo(second.name.toLowerCase(Locale.getDefault()))
        }

    private val fileSizeComparator: Comparator<in FileModel>
        get() = Comparator { first, second ->
            first.size.compareTo(second.size)
        }

    private val fileDateComparator: Comparator<in FileModel>
        get() = Comparator { first, second ->
            first.lastModified.compareTo(second.lastModified)
        }

    fun getComparator(sortMode: Int): Comparator<in FileModel> {
        return when (sortMode) {
            SORT_BY_NAME -> fileNameComparator
            SORT_BY_SIZE -> fileSizeComparator
            SORT_BY_DATE -> fileDateComparator
            else -> throw IllegalArgumentException("Unknown sort type")
        }
    }
}