package top.spco.ciel.examples;

import com.google.gson.JsonParser;
import top.spco.ciel.CielClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Run with: gradlew runExample --args="enroll grant.json private-directory" */
public final class Example {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("enroll")) {
            System.out.println(CielClient.enroll(Path.of(args[1]), Path.of(args[2])).get());
        } else if (args.length == 2 && args[0].equals("serve")) {
            serve(Path.of(args[1]));
        } else if (args.length == 5 && args[0].equals("call")) {
            try (CielClient client = CielClient.connect(Path.of(args[1])).get()) {
                String requestId = CielClient.newRequestId();
                // Persist requestId + parameters before sending in a real application.
                CielClient.CommandCall accepted = client.invokeCommand(args[2], args[3],
                        JsonParser.parseString(args[4]), 30, requestId).get();
                CielClient.CommandCall result = client.awaitCommand(accepted.id(), Duration.ofSeconds(35)).get();
                System.out.println(result + ", output=" + result.output());
            }
        } else {
            throw new IllegalArgumentException("enroll <grant.json> <private-dir> | serve <private-dir> | call <private-dir> <target-instance-id> <command-id> <JSON>");
        }
    }

    private static void serve(Path directory) throws Exception {
        try (CielClient client = new CielClient(directory)) {
            client.onState(state -> System.out.println("State: " + state))
                    .onError(error -> System.err.println(error.code()))
                    .onEvent("demo.changed", event -> System.out.println("Event publication: " + event.get("publication_id")))
                    .onCommand("demo.echo", execution -> CompletableFuture.completedFuture(
                            new CielClient.CommandResult(true, execution.input())));
            Runtime.getRuntime().addShutdownHook(new Thread(client::close));
            client.connect().get();
            client.registerEvent("demo.changed").get();
            client.subscribeEvent("demo.changed").get();
            client.registerCommand("demo.echo", "Return the input JSON").get();
            client.registerNotification("demo.ready", "服务启动", "Java SDK example").get();
            String requestId = CielClient.newRequestId();
            var notification = client.sendNotification("demo.ready", "服务启动", "Java SDK 已连接", requestId).get();
            System.out.println("Notification: " + client.getNotification(notification.get("id").getAsString()).get());
            System.out.println("Instance: " + client.identity().instanceId());
            while (client.state() != CielClient.State.CLOSED && client.state() != CielClient.State.FAILED) {
                if (client.state() == CielClient.State.READY)
                    client.publishEvent("demo.changed", JsonParser.parseString("{\"value\":1}"), CielClient.newRequestId()).get();
                Thread.sleep(5000);
            }
        }
    }
}
