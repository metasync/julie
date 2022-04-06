package com.purbon.kafka.topology;

import com.purbon.kafka.topology.api.connect.KConnectApiClient;
import com.purbon.kafka.topology.clients.ArtefactClient;
import com.purbon.kafka.topology.model.Artefact;
import com.purbon.kafka.topology.model.Topology;
import com.purbon.kafka.topology.model.artefact.KafkaConnectArtefact;
import com.purbon.kafka.topology.utils.Either;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class KafkaConnectArtefactManager extends ArtefactManager {

  private static final Logger LOGGER = LogManager.getLogger(KafkaConnectArtefactManager.class);

  public KafkaConnectArtefactManager(
      ArtefactClient client, Configuration config, String topologyFileOrDir) {
    super(client, config, topologyFileOrDir);
  }

  public KafkaConnectArtefactManager(
      Map<String, KConnectApiClient> clients, Configuration config, String topologyFileOrDir) {
    super(clients, config, topologyFileOrDir);
  }

  @Override
  Collection<? extends Artefact> loadActualClusterStateIfAvailable(ExecutionPlan plan)
      throws IOException {
    var currentState =
        config.fetchStateFromTheCluster() ? getClustersState() : plan.getConnectors();

    if (!config.fetchStateFromTheCluster()) {
      // should detect if there are divergences between the local cluster state and the current
      // status in the cluster
      detectDivergencesInTheRemoteCluster(plan);
    }

    return currentState;
  }

  private void detectDivergencesInTheRemoteCluster(ExecutionPlan plan) throws IOException {
    var remoteConnectors = getClustersState();

    var delta =
        plan.getConnectors().stream()
            .filter(localConnector -> !remoteConnectors.contains(localConnector))
            .collect(Collectors.toList());

    if (delta.size() > 0) {
      String errorMessage =
          "Your remote state has changed since the last execution, these Connector(s): "
              + StringUtils.join(delta, ",")
              + " are in your local state, but not in the cluster, please investigate!";
      LOGGER.error(errorMessage);
      throw new IOException(errorMessage);
    }
  }

  private Collection<? extends Artefact> getClustersState() throws IOException {
    List<Either> list =
        clients.values().stream()
            .map(
                client -> {
                  try {
                    Collection<? extends Artefact> artefacts = client.getClusterState();
                    if (artefacts.isEmpty()) {
                      return Either.Right(null);
                    }
                    return Either.Right(artefacts);
                  } catch (IOException ex) {
                    return Either.Left(ex);
                  }
                })
            .collect(Collectors.toList());

    List<IOException> errors =
        list.stream()
            .filter(Either::isLeft)
            .map(e -> (IOException) e.getLeft().get())
            .collect(Collectors.toList());
    if (errors.size() > 0) {
      throw new IOException(errors.get(0));
    }

    return list.stream()
        .filter(Either::isRight)
        .flatMap(
            (Function<Either, Stream<? extends Artefact>>)
                either -> {
                  Collection<? extends Artefact> artefacts =
                      (Collection<? extends Artefact>) either.getRight().get();
                  return artefacts.stream();
                })
        .map(
            artefact ->
                new KafkaConnectArtefact(
                    artefact.getPath(),
                    reverseLookup(artefact.getServerLabel()),
                    artefact.getName()))
        .collect(Collectors.toSet());
  }

  private String reverseLookup(String host) {
    return config.getKafkaConnectServers().entrySet().stream()
        .filter(e -> host.equals(e.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .get();
  }

  @Override
  Set<KafkaConnectArtefact> parseNewArtefacts(Topology topology) {
    return topology.getProjects().stream()
        .flatMap(project -> project.getConnectorArtefacts().getConnectors().stream())
        .collect(Collectors.toSet());
  }

  @Override
  boolean isAllowDelete() {
    return config.isAllowDeleteConnectArtefacts();
  }

  @Override
  String rootPath() {
    return Files.isDirectory(Paths.get(topologyFileOrDir))
        ? topologyFileOrDir
        : new File(topologyFileOrDir).getParent();
  }

  @Override
  public void printCurrentState(PrintStream out) throws IOException {
    out.println("List of Connectors:");
    getClustersState().forEach(out::println);
  }
}
