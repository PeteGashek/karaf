/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
package org.apache.karaf.features.command;

import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.lifecycle.Reference;

public abstract class FeaturesCommandSupport implements Action {

    @Reference
    private FeaturesService featuresService;

    @Override
    public Object execute() throws Exception {
        if (featuresService == null) {
            throw new IllegalStateException("FeaturesService not found");
        }
        doExecute(featuresService);
        return null;
    }

    protected abstract void doExecute(FeaturesService admin) throws Exception;

    public void setFeaturesService(FeaturesService featuresService) {
        this.featuresService = featuresService;
    }
}
