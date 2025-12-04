package org.example.service;

import ai.djl.MalformedModelException;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.translate.TranslateException;
import com.example.grpc.DataTransferProto;
import com.example.grpc.DataTransferServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.counter.VehicleCounterService;
import org.example.counter_v2.VehicleCounterService_v1;

import java.io.IOException;

public class  GrpcServerController {

    VehicleCounterService vehicleCounterService = new VehicleCounterService();
    private Server server;

    public GrpcServerController(int port) throws Exception {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(new DataTransferServiceGrpc.DataTransferServiceImplBase() {
                        @Override
                        public void receiveData(DataTransferProto.DataRequest request,
                                                StreamObserver<DataTransferProto.DataResponse> responseObserver) {
                            byte[] data = request.getData().toByteArray();

                            // Gọi handler và nhận int
                            int result = 0;
                            result = vehicleCounterService.receiveImage(data);

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