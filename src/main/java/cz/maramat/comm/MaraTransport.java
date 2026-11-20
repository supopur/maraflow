package cz.maramat.comm;

import com.fazecast.jSerialComm .SerialPort;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

// Swappable transport abstraction for MaraProt v1 (USB CDC, TCP/IP, RS-485).
public interface MaraTransport extends Closeable {

    void open() throws IOException;
    boolean isOpen();
    void write(byte[] data) throws IOException;
    int read(byte[] buf, int offset, int len, int timeoutMs) throws IOException;

    // Lists available serial / USB CDC port paths on the host OS.
    static List<String> listSerialPorts() {
        List<String> ports = new ArrayList<>();
        for (SerialPort sp : SerialPort.getCommPorts()) {
            String path = sp.getSystemPortPath();
            if (path != null && !path.isBlank()) {
                ports.add(path);
            } else {
                ports.add(sp.getSystemPortName());
            }
        }
        return ports;
    }

    // USB CDC / RS-485 serial transport backed by jSerialComm.
    final class SerialCdcTransport implements MaraTransport {
        private final String portName;
        private final int baudRate;
        private SerialPort port;

        public SerialCdcTransport(String portName, int baudRate) {
            this.portName = portName;
            this.baudRate = baudRate;
        }

        @Override
        public void open() throws IOException {
            port = SerialPort.getCommPort(portName);
            port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 500);
            if (!port.openPort()) {
                throw new IOException("Failed to open serial port: " + portName);
            }
            // Assert DTR/RTS so STM32 USB CDC starts forwarding packets cleanly
            port.setDTR();
            port.setRTS();
        }

        @Override
        public boolean isOpen() {
            return port != null && port.isOpen();
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (!isOpen()) throw new IOException("Serial port not open");
            int written = port.writeBytes(data, data.length);
            if (written != data.length) {
                throw new IOException("Short serial write: " + written + " / " + data.length);
            }
        }

        @Override
        public int read(byte[] buf, int offset, int len, int timeoutMs) throws IOException {
            if (!isOpen()) throw new IOException("Serial port not open");
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutMs, 500);
            int n = port.readBytes(buf, len, offset);
            if (n < 0) throw new IOException("Serial read error");
            return n;
        }

        @Override
        public void close() {
            if (port != null && port.isOpen()) {
                port.closePort();
            }
        }
    }

    // TCP/IP transport for Ethernet-connected F407PLC.
    final class TcpTransport implements MaraTransport {
        private final String host;
        private final int port;
        private Socket socket;
        private InputStream in;
        private OutputStream out;

        public TcpTransport(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void open() throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(1000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        @Override
        public boolean isOpen() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (!isOpen()) throw new IOException("TCP socket not open");
            out.write(data);
            out.flush();
        }

        @Override
        public int read(byte[] buf, int offset, int len, int timeoutMs) throws IOException {
            if (!isOpen()) throw new IOException("TCP socket not open");
            socket.setSoTimeout(timeoutMs);
            try {
                int n = in.read(buf, offset, len);
                return Math.max(0, n);
            } catch (java.net.SocketTimeoutException e) {
                return 0;
            }
        }

        @Override
        public void close() throws IOException {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
