/*
 *  Copyright 2016 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.netflix.ndbench.plugin.dyno;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.archaius.api.PropertyFactory;
import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.HostSupplier;
import com.netflix.dyno.connectionpool.TokenMapSupplier;
import com.netflix.dyno.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.dyno.connectionpool.impl.lb.AbstractTokenMapSupplier;
import com.netflix.dyno.jedis.DynoJedisClient;
import com.netflix.ndbench.api.plugin.DataGenerator;
import com.netflix.ndbench.api.plugin.NdBenchClient;
import com.netflix.ndbench.api.plugin.annotations.NdBenchClientPlugin;
import com.netflix.ndbench.api.plugin.common.NdBenchConstants;
import com.netflix.ndbench.plugin.util.TokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author vchella
 */
@Singleton
@NdBenchClientPlugin("DynoJedis")
public class DynoJedis implements NdBenchClient {
    private static final Logger logger = LoggerFactory.getLogger(DynoJedis.class);

    private static final String ResultOK = "Ok";
    private static final String CacheMiss = null;

    private static final String ClusterName = "dynomite_redis";

    private DataGenerator dataGenerator;

    private int totalNodes, dynoPort;

    private final BigInteger totalTokens = new BigInteger("4294967295");

    private String domain;

    private final AtomicReference<DynoJedisClient> jedisClient = new AtomicReference<DynoJedisClient>(null);

    protected PropertyFactory propertyFactory;

    private String localRack="rack01";
    private String replicaRack="rack02";

    @Inject
    public DynoJedis(PropertyFactory propertyFactory) {
        this.propertyFactory = propertyFactory;
    }

    @Override
    public void init(DataGenerator dataGenerator) throws Exception {
        this.dataGenerator = dataGenerator;
        if (jedisClient.get() != null) {
            return;
        }

        logger.info("Initing dyno jedis client");

        logger.info("\nDynomite Cluster: " + ClusterName);

        totalNodes = propertyFactory.getProperty(NdBenchConstants.PROP_NAMESPACE + "dyno.totalNodes").asInteger(6).get();
        dynoPort = propertyFactory.getProperty(NdBenchConstants.PROP_NAMESPACE + "dyno.dynoPort").asInteger(50172).get();
        domain = propertyFactory.getProperty(NdBenchConstants.PROP_NAMESPACE + "dyno.domain").asString(null).get();

        HostSupplier hSupplier = new HostSupplier() {

            @Override
            public List<Host> getHosts() {

                List<Host> hosts = new ArrayList<Host>();
                /**
                 * now add the dyno hosts based on what we have. qredis is appended with two digit value of int
                 * then append the domain name for host.
                 * all even nodes are replica and odd are primary
                 */
                for (int i = 1; i <= totalNodes; i++) {
                    String hostname = "qredis" + new DecimalFormat("00").format(i) + "." + domain;
                    String rack = (i % 2 == 0) ? replicaRack : localRack;
                    hosts.add(new Host(hostname, dynoPort, rack, Host.Status.Up));
                }

                return hosts;
            }
        };

        DynoJedisClient jClient = new DynoJedisClient.Builder().withApplicationName(ClusterName)
                .withDynomiteClusterName(ClusterName)
                .withHostSupplier(hSupplier)
                .withCPConfig(buildCpConfig())
                .build();

        jedisClient.set(jClient);

    }

    public ConnectionPoolConfigurationImpl buildCpConfig() {
        ConnectionPoolConfigurationImpl connectionPoolConfiguration =
                new ConnectionPoolConfigurationImpl(ClusterName)
                .withTokenSupplier(buildTokenSupplier());

        connectionPoolConfiguration.setLocalRack(localRack);


        return connectionPoolConfiguration;
    }

    public AbstractTokenMapSupplier buildTokenSupplier() {
        List<TokenMapper> tokenMappers = Lists.newArrayList();

        for(int i=1;i<=totalNodes;i++) {
            String hostname = "qredis" + new DecimalFormat("00").format(i) + "." + domain;
            String rack = (i % 2 == 0) ? replicaRack : localRack;
            BigInteger tokenUnit = totalTokens.divide(BigInteger.valueOf(totalNodes/2));
            int multiply = (i%(totalNodes/2));
            BigInteger token = tokenUnit.multiply(BigInteger.valueOf(multiply));
            tokenMappers.add(new TokenMapper(token.toString(), hostname, String.valueOf(dynoPort), rack, rack, ClusterName));
        }

        ObjectMapper objectMapper = new ObjectMapper();

        final String json;
        try {
            json = objectMapper.writeValueAsString(tokenMappers);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }


        return new AbstractTokenMapSupplier(localRack, dynoPort) {
            public String getTopologyJsonPayload(Set<Host> activeHosts) {
                return json;
            }

            @Override
            public String getTopologyJsonPayload(String hostname) {
                return json;
            }
        };
    }

    @Override
    public String readSingle(String key) throws Exception {

        String res = jedisClient.get().get(key);

        if (res != null) {
            if (res.isEmpty()) {
                throw new Exception("Data retrieved is not ok ");
            }
        } else {
            return CacheMiss;
        }

        return ResultOK;

    }

    @Override
    public String writeSingle(String key) throws Exception {
        String result = jedisClient.get().set(key, dataGenerator.getRandomValue());

        if (!"OK".equals(result)) {
            logger.error("SET_ERROR: GOT " + result + " for SET operation");
            throw new RuntimeException(String.format("DynoJedis: value %s for SET operation is NOT VALID", key));

        }

        return result;
    }

    /**
     * Perform a bulk read operation
     *
     * @return a list of response codes
     * @throws Exception
     */
    public List<String> readBulk(final List<String> keys) throws Exception {
        throw new UnsupportedOperationException("bulk operation is not supported");
    }

    /**
     * Perform a bulk write operation
     *
     * @return a list of response codes
     * @throws Exception
     */
    public List<String> writeBulk(final List<String> keys) throws Exception {
        throw new UnsupportedOperationException("bulk operation is not supported");
    }

    /**
     * Shutdown the client
     */
    @Override
    public void shutdown() throws Exception {
        if (jedisClient.get() != null) {
            jedisClient.get().stopClient();
            jedisClient.set(null);
        }
    }

    /**
     * Get connection information
     */
    @Override
    public String getConnectionInfo() throws Exception {
        return String.format("DynoJedis Plugin - ConnectionInfo ::Cluster Name - %s", ClusterName);
    }

    @Override
    public String runWorkFlow() throws Exception {
        return null;
    }
}
