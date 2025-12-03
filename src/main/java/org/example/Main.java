package org.example;

import org.example.service.GrpcServerController;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    static void main() throws Exception {


        GrpcServerController server = new GrpcServerController(9090);
        System.out.println("Hello World");
    }
}
