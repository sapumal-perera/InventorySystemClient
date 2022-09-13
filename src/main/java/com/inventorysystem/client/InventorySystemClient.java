package com.inventorysystem.client;

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

import com.stock.trade.name.service.NameServiceClient;
import com.trade.communication.grpc.generated.Empty;
import com.trade.communication.grpc.generated.OrderRequest;
import com.trade.communication.grpc.generated.OrderResponse;
import com.trade.communication.grpc.generated.StockMarketRegisteredRecord;
import com.trade.communication.grpc.generated.StockTradingServiceGrpc;
import dnl.utils.text.table.TextTable;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class InventorySystemClient {

    public static final String NAME_SERVICE_ADDRESS = "http://localhost:2379";
    private ManagedChannel channel = null;
    StockTradingServiceGrpc.StockTradingServiceBlockingStub clientStub = null;
    String host = null;
    int port = -1;
    private DecimalFormat decimalFormat = new DecimalFormat( "#.##" );
    String incrementOrderIdFileName = "incrementOrderIdFile.tmp";

    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println( "Stock Trading Client started!" );

        InventorySystemClient client = new InventorySystemClient();
        client.initializeConnection();
        client.processUserRequests();
        client.closeConnection();
    }

    public InventorySystemClient() throws IOException, InterruptedException {
        fetchServerDetails();
    }

    public void initializeConnection() {
        System.out.println( "Initializing Connecting to server at " + host + ":" + port );
        channel = ManagedChannelBuilder.forAddress( "localhost", 11436 )
                .usePlaintext()
                .build();
        clientStub = StockTradingServiceGrpc.newBlockingStub( channel );
        //ETCD connection
        channel.getState( true );
    }

    //ETCD connection
    private void fetchServerDetails() throws IOException, InterruptedException {
        NameServiceClient client = new NameServiceClient( NAME_SERVICE_ADDRESS );
        NameServiceClient.ServiceDetails serviceDetails = null;
        try {
            serviceDetails = client.findService( "StockTradingService" );
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
        while ( true ) {
            Scanner userInput = new Scanner( System.in );

            //ETCD connection
            ConnectivityState state = channel.getState( true );
            System.out.println("state" + state);
            while ( state != ConnectivityState.READY ) {
             //   System.out.println("state" + state);
                System.out.println( "Service unavailable, looking for a service provider.." );
                fetchServerDetails();
                initializeConnection();
                Thread.sleep( 5000 );
                state = channel.getState( true );
            }

            // Print existing registered stocks in the system
            Iterator<StockMarketRegisteredRecord> registeredStocks = clientStub.getStockMarketRegisteredData( Empty.newBuilder().build() );
            System.out.println( "-----------------------------------------------------------------------------" );
            System.out.println( "------------------------------ Registered Stocks ----------------------------" );
            String[] columns = {
                    "Stock Symbol",
                    "Total Quantity"
            };
            List<String[]> stockList = new ArrayList<>();
            while ( registeredStocks.hasNext() ) {
                StockMarketRegisteredRecord record = registeredStocks.next();
                String[] arr1 = new String[2];
                arr1[0] = record.getName();
                arr1[1] = String.valueOf( record.getQuantity() );
                stockList.add( arr1 );
            }
            TextTable stockTable = new TextTable(columns, stockList.toArray( new Object[stockList.size()][] ));
            stockTable.printTable();
            System.out.println( "-----------------------------------------------------------------------------" );

            //display orders in the orderBook
            System.out.println( "--------------------- Pending Stock Order Requests --------------------------" );
            Iterator<OrderRequest> orderBookRecords = clientStub.getOrderBookRecords( Empty.newBuilder().build() );

            String[] orderValuesHeading = {
                    "Order Id",
                    "Order Type",
                    "Stock Symbol",
                    "Quantity",
                    "Price(per stock)",
            };
            List<String[]> orderList = new ArrayList<>();
            while ( orderBookRecords.hasNext() ) {
                OrderRequest order = orderBookRecords.next();
                String[] arr = new String[6];
                arr[0] = String.valueOf( order.getId() );
                arr[1] = order.getStockSymbol().toUpperCase();
                arr[2] = order.getOrderType();
                arr[3] = String.valueOf( order.getQuantity() );
                arr[4] = decimalFormat.format( order.getPrice() );
                orderList.add( arr );
            }
            TextTable ordersTable = new TextTable( orderValuesHeading, orderList.toArray( new Object[orderList.size()][] ) );
            ordersTable.printTable();
            System.out.println( "-----------------------------------------------------------------------------" );

            System.out.println( "Select an option( 1:- Add Order, 2:- Delete Order): " );
            int orderAction = userInput.nextInt();

            //get user inputs to add order
            if(orderAction == 1) {
                //get user inputs to add order
                System.out.println("Select Order Type( 1:- SELL Order, 2:- BUY Order): ");
                int orderRequestType = userInput.nextInt();
                System.out.println("Enter Stock Symbol: ");
                userInput.nextLine();
                String stockSymbol = userInput.nextLine().trim();
                System.out.println("Quantity of the stocks: ");
                int quantity = userInput.nextInt();
                System.out.println("Price(per stock): ");
                double price = userInput.nextDouble();

                OrderRequest request = OrderRequest
                        .newBuilder()
                        .setId(getOrderId())
                        .setStockSymbol(stockSymbol)
                        .setOrderType(orderRequestType == 1 ? "SELL" : "BUY")
                        .setPrice(price)
                        .setQuantity(quantity)
                        .build();

                OrderResponse response = clientStub.tradeOperation(request);
                System.out.println("Status of the order request: " + response.getStatus());
                Thread.sleep(1000);
            }
            //get user inputs to delete order
            else if(orderAction == 2){
                System.out.println("Enter Stock Symbol: ");
                userInput.nextLine();
                String deleteStockSymbol = userInput.nextLine().trim();
                System.out.println("Enter Order ID to delete: ");
                int deleteRequestID = userInput.nextInt();

                OrderRequest request = OrderRequest
                        .newBuilder()
                        .setId(deleteRequestID)
                        .setStockSymbol(deleteStockSymbol)
                        .setOrderType("DELETE")
                        .setPrice(0)
                        .setQuantity(0)
                        .build();

                OrderResponse response = clientStub.tradeOperation(request);
                System.out.println("Status of the order request: " + response.getStatus());
                Thread.sleep(1000);
            }
        }
    }

    //set auto increment orderID
    private int getOrderId() throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes( Paths.get( incrementOrderIdFileName ) );
        }
        catch ( NoSuchFileException e ) {
            updateOrderIds( 1 );
            return 1;
        }
        String value = new String( bytes );
        int orderId = Integer.parseInt( value.trim() ) + 1;
        updateOrderIds( orderId );
        return orderId;
    }

    //update id in the file
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
