package org.example.service;

import com.example.grpc.DataTransferProto;
import com.example.grpc.DataTransferServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.function.Consumer;

public class GrpcServerService {
    private Server server;
    private Thread serverThread;

    public GrpcServerService(int port, Consumer<byte[]> onDataReceived) {
        serverThread = new Thread(() -> {
            try {
                server = ServerBuilder.forPort(port)
                        .addService(new DataTransferServiceGrpc.DataTransferServiceImplBase() {
                            @Override
                            public void receiveData(DataTransferProto.DataRequest request,
                                                    StreamObserver<DataTransferProto.Empty> responseObserver) {
                                byte[] data = request.getData().toByteArray();

                                // Gọi callback lambda
                                onDataReceived.accept(data);

                                responseObserver.onNext(DataTransferProto.Empty.newBuilder().build());
                                responseObserver.onCompleted();
                            }
                        })
                        .build()
                        .start();
                System.out.println("Server started on port " + port);
                server.awaitTermination();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }
}