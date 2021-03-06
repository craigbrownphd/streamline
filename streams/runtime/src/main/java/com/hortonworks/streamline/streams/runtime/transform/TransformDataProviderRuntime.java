/**
  * Copyright 2017 Hortonworks.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at

  *   http://www.apache.org/licenses/LICENSE-2.0

  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
 **/

package com.hortonworks.streamline.streams.runtime.transform;

import com.hortonworks.streamline.streams.layout.Transform;

/**
 * Data provider for {@link Transform} which can be used for lookups.
 */
public interface TransformDataProviderRuntime {

    /**
     * Prepare resources which can be used in retrieving values from data store.
     */
    void prepare();

    /**
     * Retrieves a value for a given key from a data store.
     *
     * @param key
     */
    Object get(Object key);

    /**
     * cleanup any resources held by this instance.
     */
    void cleanup();

}
