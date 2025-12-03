package org.example.service;

import ai.djl.MalformedModelException;
import ai.djl.repository.zoo.ModelNotFoundException;
import com.example.grpc.DataTransferProto;
import com.example.grpc.DataTransferServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.counter.VehicleCounterService1;

import java.io.IOException;

public class  GrpcServerController {

    VehicleCounterService1 vehicleCounterService = VehicleCounterService1.instanse;
    private Server server;

    public GrpcServerController(int port) throws ModelNotFoundException, MalformedModelException, IOException {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(new DataTransferServiceGrpc.DataTransferServiceImplBase() {
                        @Override
                        public void receiveData(DataTransferProto.DataRequest request,
                                                StreamObserver<DataTransferProto.DataResponse> responseObserver) {
                            byte[] data = request.getData().toByteArray();

                            // Gọi handler và nhận int
                            int result = vehicleCounterService.receiveImage(data);

                            // Trả về int
                            DataTransferProto.DataResponse response = DataTransferProto.DataResponse.newBuilder()
                                    .setValue(result)
                                    .build();

                            responseObserver.onNext(response);
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

    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }
}