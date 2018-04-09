package com.netflix.ndbench.plugin.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.netflix.archaius.api.PropertyFactory;
import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.HostSupplier;
import com.netflix.dyno.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.dyno.connectionpool.impl.lb.AbstractTokenMapSupplier;
import com.netflix.dyno.jedis.DynoJedisClient;
import com.netflix.ndbench.api.plugin.common.NdBenchConstants;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Set;

public class DynoClientHelper {

    public static final BigInteger totalTokens = new BigInteger("4294967295");

    public static final String localRack = "rack01";
    public static final String replicaRack = "rack02";

    public static final String ClusterName = "dynomite_redis";


    public static DynoJedisClient buildDynoJedisClient(PropertyFactory propertyFactory) {

        int totalNodes = propertyFactory.getProperty(NdBenchConstants.PROP_NAMESPACE + "dyno.totalNodes").asInteger(6).get();
        int dynoPort = propertyFactory.getProperty(NdBenchConstants.PROP_NAMESPACE + "dyno.dynoPort").asInteger(50172).get();
        String domain = propertyFactory.getProperty(NdBenchConstants.PROP_NAMESPACE + "dyno.domain").asString(null).get();

        HostSupplier hSupplier = () -> {

            List<Host> hosts = Lists.newArrayList();
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
        };

        return new DynoJedisClient.Builder().withApplicationName(ClusterName)
                .withDynomiteClusterName(ClusterName)
                .withHostSupplier(hSupplier)
                .withCPConfig(buildCpConfig(totalNodes, domain, dynoPort))
                .build();
    }

    public static ConnectionPoolConfigurationImpl buildCpConfig(int totalNodes, String domain, int dynoPort) {
        ConnectionPoolConfigurationImpl connectionPoolConfiguration =
                new ConnectionPoolConfigurationImpl(ClusterName)
                        .withTokenSupplier(buildTokenSupplier(totalNodes, domain, totalTokens, dynoPort));

        connectionPoolConfiguration.setLocalRack(localRack);


        return connectionPoolConfiguration;
    }

    public static AbstractTokenMapSupplier buildTokenSupplier(int totalNodes, String domain, BigInteger totalTokens, int dynoPort) {
        List<TokenMapper> tokenMappers = Lists.newArrayList();

        BigInteger tokenUnit = totalTokens.divide(BigInteger.valueOf(totalNodes / 2));
        int seq = 0;
        for (int i = 1; i <= totalNodes; i++) {
            seq++;
            int j=i+1;
            String hostname;
            BigInteger token = tokenUnit.multiply(BigInteger.valueOf(seq));


            hostname = "qredis" + new DecimalFormat("00").format(i) + "." + domain;
            //add primary
            tokenMappers.add(new TokenMapper(token.toString(), hostname, String.valueOf(dynoPort), localRack, localRack, ClusterName));

            hostname = "qredis" + new DecimalFormat("00").format(j) + "." + domain;
            //add replica
            tokenMappers.add(new TokenMapper(token.toString(), hostname, String.valueOf(dynoPort), replicaRack, replicaRack, ClusterName));
            i = j;
        }

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            final String json = objectMapper.writeValueAsString(tokenMappers);

            return new AbstractTokenMapSupplier(localRack, dynoPort) {
                public String getTopologyJsonPayload(Set<Host> activeHosts) {
                    return json;
                }

                @Override
                public String getTopologyJsonPayload(String hostname) {
                    return json;
                }
            };

        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        return null;
    }
}
