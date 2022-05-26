/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir

import com.google.android.fhir.index.getSearchParamList
import com.google.android.fhir.search.Search
import org.hl7.fhir.r4.model.Resource

interface FhirXQuery : FhirEngine {
  suspend fun <R : Resource> searchByString(fhirXQueryModel: FhirXQueryModel): List<R> {
    val searchObject = Search(fhirXQueryModel.type, fhirXQueryModel.count, fhirXQueryModel.from)
    val searchParameter =
      getSearchParamList(fhirXQueryModel.resource).filter { it.name == fhirXQueryModel.search }
    return this.search(searchObject)<R> { searchParameter }
  }
}
