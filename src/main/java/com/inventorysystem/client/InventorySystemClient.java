package com.inventorysystem.client;

import com.inventorymanager.communication.grpc.generated.*;
import com.inventorysystem.service.NameServiceClient;
import dnl.utils.text.table.TextTable;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;

public class InventorySystemClient {

    public static final String NAME_SERVICE_ADDRESS = "http://localhost:2379";
    private ManagedChannel channel = null;
    InventoryServiceGrpc.InventoryServiceBlockingStub clientStub = null;
    String host = null;
    int port = -1;
    private DecimalFormat decimalFormat = new DecimalFormat( "#.##" );
    String incrementOrderIdFileName = "incrementOrderIdFile.tmp";

    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println( "Client started.....!" );

        InventorySystemClient client = new InventorySystemClient();
        client.initializeConnection();
        client.processUserRequests();
        client.closeConnection();
    }

    public InventorySystemClient() throws IOException, InterruptedException {
        fetchServerDetails();
    }

    public void initializeConnection() {
        System.out.println( "Connection established to server host " + host + ":" + port );
        channel = ManagedChannelBuilder.forAddress( host, port )
                .usePlaintext()
                .build();
        clientStub = InventoryServiceGrpc.newBlockingStub( channel );
        //ETCD connection
        channel.getState( true );
    }


    private void fetchServerDetails() throws IOException, InterruptedException {
        NameServiceClient client = new NameServiceClient( NAME_SERVICE_ADDRESS );
        NameServiceClient.ServiceDetails serviceDetails = null;
        try {
            serviceDetails = client.findService( "InventoryManagementSystem" );
        }
        catch ( ConnectException e ) {
            System.out.println("ETCD server cannot find in " + NAME_SERVICE_ADDRESS );
            e.printStackTrace();
        }
        host = Objects.requireNonNull( serviceDetails ).getIPAddress();
        port = serviceDetails.getPort();
    }

    public void closeConnection() {
        channel.shutdown();
    }

    public void processUserRequests() throws InterruptedException, IOException {
        while (true) {
            Scanner userInput = new Scanner(System.in);

            //ETCD connection
            ConnectivityState state = channel.getState(true);

         //   System.out.println("connection state: " + state.toString());
           // System.out.println(state);

            while (state != ConnectivityState.READY) {
                System.out.println(state);

                System.out.println("Service unavailable, looking for a service provider..");
                fetchServerDetails();
                initializeConnection();
                Thread.sleep(5000);
                state = channel.getState(true);
            }

            getInventory();
            System.out.println("Select your choice : ");
            System.out.println("____________________");
            System.out.println("Update Item Quantity | 1 | : ");
            System.out.println("Put a Order | 2 | : ");
            System.out.println("View Inventory | 3 | : ");
            int option = userInput.nextInt();

            if (option == 1) {
                System.out.println("Enter Item Code: ");
                userInput.nextLine();
                String itemCode = userInput.nextLine().trim();
                System.out.println("Enter item quantity to add: ");
                int quantity = userInput.nextInt();

                InventoryOperationRequest request = InventoryOperationRequest
                        .newBuilder()
                        .setItemCode(itemCode)
                        .setOperationType(OperationType.UPDATE.name())
                        .setQuantity(quantity)
                        .build();

                InventoryOperationResponse response = clientStub.updateInventory(request);
                System.out.println("Response status : " + response.getStatus());
                Thread.sleep(1000);
            }
            else if (option == 2) {
                System.out.println("Enter ItemCode: ");
                userInput.nextLine();
                String ItemCode = userInput.nextLine().trim();
                System.out.println("Quantity of the order: ");
                int quantity = userInput.nextInt();

                InventoryOperationRequest request = InventoryOperationRequest
                        .newBuilder()
                        .setItemCode(ItemCode)
                        .setOperationType(OperationType.ORDER.name())
                        .setQuantity(quantity)
                        .build();

                InventoryOperationResponse response = clientStub.orderItem(request);
                System.out.println("Status of the request: " + response.getStatus());
                Thread.sleep(1000);
            } else if (option == 3) {
                getInventory();
                Thread.sleep(1000);
            }
        }

    }

    private void getInventory(){
        Iterator<Item> registeredItems = clientStub.getInventory( Empty.newBuilder().build() );
        System.out.println( "-----------------------------------------------------------------------------" );
        System.out.println( "------------------------------ Inventory----------------------------" );
        String[] columns = {
                "Item Code",
                "Name",
                "Quantity",
                "Price",
        };
        List<String[]> itemList = new ArrayList<>();
        while ( registeredItems.hasNext() ) {
            Item record = registeredItems.next();
            String[] arr1 = new String[4];
            arr1[0] = record.getItemCode();
            arr1[1] = record.getName();
            arr1[2] = String.valueOf( record.getQuantity());
            arr1[3] = String.valueOf( record.getPrice());
            itemList.add( arr1 );
        }
        TextTable inventoryTable = new TextTable(columns, itemList.toArray( new Object[itemList.size()][] ));
        inventoryTable.printTable();
        System.out.println( "-----------------------------------------------------------------------------" );
    }

//    private int CreateRequestId() throws IOException {
//    }

    private void updateOrderIds(int id) throws IOException {
        RandomAccessFile fileStream = new RandomAccessFile( incrementOrderIdFileName, "rw" );
        FileChannel channel = fileStream.getChannel();
        FileLock lock = null;
        try {
            lock = channel.tryLock();
        }
        catch ( final OverlappingFileLockException e ) {
            fileStream.close();
            channel.close();
        }
        fileStream.writeChars( id + " " );
        Objects.requireNonNull( lock ).release();

        fileStream.close();
        channel.close();
    }
}