/**
 * Copyright 2016 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.ndbench.plugin.dyno;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.archaius.api.PropertyFactory;
import com.netflix.dyno.jedis.DynoJedisClient;
import com.netflix.ndbench.api.plugin.DataGenerator;
import com.netflix.ndbench.api.plugin.NdBenchClient;
import com.netflix.ndbench.api.plugin.annotations.NdBenchClientPlugin;
import com.netflix.ndbench.plugin.util.DynoClientHelper;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This pluging performs hash operations (HMSET/HGETALL) inside a pipeline of
 * size MAX_PIPE_KEYS against Dynomite.
 *
 * @author ipapapa
 *
 */
@Singleton
@NdBenchClientPlugin("DynoHashPipeline")
public class DynoJedisHashPipeline implements NdBenchClient {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(DynoJedisHashPipeline.class);

    private static final String HM_KEY_PREFIX = "HM__";

    protected PropertyFactory propertyFactory;

    private final AtomicReference<DynoJedisClient> jedisClient = new AtomicReference<DynoJedisClient>(null);

    private DataGenerator dataGenerator;

    @Inject
    public DynoJedisHashPipeline(PropertyFactory propertyFactory) {
        this.propertyFactory = propertyFactory;
    }

    @Override
    public void init(DataGenerator dataGenerator) throws Exception {
        this.dataGenerator = dataGenerator;
        if (jedisClient.get() != null) {
            return;
        }

        logger.info("Initing dyno jedis client");

        logger.info("\nDynomite Cluster: " + DynoClientHelper.ClusterName);

        DynoJedisClient jClient = DynoClientHelper.buildDynoJedisClient(propertyFactory);

        jedisClient.set(jClient);
    }

    @Override
    public String readSingle(String key) throws Exception {
        DynoJedisUtils jedisUtils = new DynoJedisUtils(jedisClient);
        return jedisUtils.pipelineReadHGETALL(key, HM_KEY_PREFIX);
    }

    @Override
    public String writeSingle(String key) throws Exception {
        DynoJedisUtils jedisUtils = new DynoJedisUtils(jedisClient);
        return jedisUtils.pipelineWriteHMSET(key, dataGenerator, HM_KEY_PREFIX);
    }

    /**
     * Perform a bulk read operation
     * @return a list of response codes
     * @throws Exception
     */
    public List<String> readBulk(final List<String> keys) throws Exception {
        throw new UnsupportedOperationException("bulk operation is not supported");
    }

    /**
     * Perform a bulk write operation
     * @return a list of response codes
     * @throws Exception
     */
    public List<String> writeBulk(final List<String> keys) throws Exception {
        throw new UnsupportedOperationException("bulk operation is not supported");
    }

    @Override
    public void shutdown() throws Exception {
        if (jedisClient.get() != null) {
            jedisClient.get().stopClient();
            jedisClient.set(null);
        }
    }

    @Override
    public String getConnectionInfo() throws Exception {
        return String.format("Cluster Name - %s", DynoClientHelper.ClusterName);
    }

    @Override
    public String runWorkFlow() throws Exception {
        return null;
    }

}
