package net.md_5.bungee;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Getter;
import lombok.Synchronized;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.packet.*;

public class UserConnection extends GenericConnection implements ProxiedPlayer
{

    public final Packet2Handshake handshake;
    public Queue<DefinedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    public List<byte[]> loginPackets = new ArrayList<>();
    @Getter
    private ServerConnection server;
    private UpstreamBridge upBridge;
    private DownstreamBridge downBridge;
    // reconnect stuff
    private int clientEntityId;
    private int serverEntityId;
    private volatile boolean reconnecting;
    // ping stuff
    private int trackingPingId;
    private long pingTime;
    @Getter
    private int ping;
    // Permissions
    private final Collection<String> groups = new HashSet<>();
    private final Map<String, Boolean> permissions = new HashMap<>();

    public UserConnection(Socket socket, PacketInputStream in, OutputStream out, Packet2Handshake handshake, List<byte[]> loginPackets)
    {
        super(socket, in, out);
        this.handshake = handshake;
        name = handshake.username;
        displayName = handshake.username;
        this.loginPackets = loginPackets;
        BungeeCord.instance.connections.put(name, this);
        BungeeCord.instance.tabListHandler.onJoin(this);
    }

    @Override
    public void setDisplayName(String name)
    {
        ProxyServer.getInstance().getTabListHandler().onDisconnect(this);
        displayName = name;
        ProxyServer.getInstance().getTabListHandler().onConnect(this);
    }

    public void connect(Server server)
    {
        ServerConnectEvent event = new ServerConnectEvent(this, server);
        BungeeCord.getInstance().getPluginManager().callEvent(event);
        InetSocketAddress addr = BungeeCord.instance.config.getServer(event.getNewServer());
        connect(server, addr);
    }

    private void connect(String name, InetSocketAddress serverAddr)
    {
        BungeeCord.instance.tabListHandler.onServerChange(this);
        try
        {
            reconnecting = true;

            if (server != null)
            {
                out.write(new Packet9Respawn((byte) 1, (byte) 0, (byte) 0, (short) 256, "DEFAULT").getPacket());
                out.write(new Packet9Respawn((byte) -1, (byte) 0, (byte) 0, (short) 256, "DEFAULT").getPacket());
            }

            ServerConnection newServer = ServerConnection.connect(this, name, serverAddr, handshake, true);
            if (server == null)
            {
                clientEntityId = newServer.loginPacket.entityId;
                serverEntityId = newServer.loginPacket.entityId;
                out.write(newServer.loginPacket.getPacket());
                upBridge = new UpstreamBridge();
                upBridge.start();
            } else
            {
                try
                {
                    downBridge.interrupt();
                    downBridge.join();
                } catch (InterruptedException ie)
                {
                }

                server.disconnect("Quitting");

                Packet1Login login = newServer.loginPacket;
                serverEntityId = login.entityId;
                out.write(new Packet9Respawn(login.dimension, login.difficulty, login.gameMode, (short) 256, login.levelType).getPacket());
            }
            reconnecting = false;
            downBridge = new DownstreamBridge();
            if (server != null)
            {
                List<UserConnection> conns = BungeeCord.instance.connectionsByServer.get(server.name);
                if (conns != null)
                {
                    conns.remove(this);
                }
            }
            server = newServer;
            List<UserConnection> conns = BungeeCord.instance.connectionsByServer.get(server.name);
            if (conns == null)
            {
                conns = new ArrayList<>();
                BungeeCord.instance.connectionsByServer.put(server.name, conns);
            }
            if (!conns.contains(this))
            {
                conns.add(this);
            }
            downBridge.start();
        } catch (KickException ex)
        {
            destroySelf(ex.getMessage());
        } catch (Exception ex)
        {
            destroySelf("Could not connect to server - " + ex.getClass().getSimpleName());
            ex.printStackTrace(); // TODO: Remove
        }
    }

    private void destroySelf(String reason)
    {
        if (BungeeCord.instance.isRunning)
        {
            BungeeCord.instance.connections.remove(name);
            if (server != null)
            {
                List<UserConnection> conns = BungeeCord.instance.connectionsByServer.get(server.name);
                if (conns != null)
                {
                    conns.remove(this);
                }
            }
        }
        disconnect(reason);
        if (server != null)
        {
            server.disconnect("Quitting");
            ProxyServer.getInstance().getReconnectHandler().setServer(this);
        }
    }

    @Override
    public void disconnect(String reason)
    {
        ProxyServer.getInstance().getTabListHandler().onDisconnect(this);
        super.disconnect(reason);
    }

    @Override
    public void sendMessage(String message)
    {
        packetQueue.add(new Packet3Chat(message));
    }

    @Override
    public void sendData(String channel, byte[] data)
    {
        server.packetQueue.add(new PacketFAPluginMessage(channel, data));
    }

    @Override
    public InetSocketAddress getAddress()
    {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override
    @Synchronized(value = "permMutex")
    public Collection<String> getGroups()
    {
        return Collections.unmodifiableCollection(groups);
    }

    @Override
    @Synchronized(value = "permMutex")
    public void addGroups(String... groups)
    {
        for (String group : groups)
        {
            this.groups.add(group);
            for (String permission : ProxyServer.getInstance().getConfigurationAdapter().getPermissions(group))
            {
                setPermission(permission, true);
            }
        }
    }

    @Override
    @Synchronized(value = "permMutex")
    public void removeGroups(String... groups)
    {
        for (String group : groups)
        {
            this.groups.remove(group);
            for (String permission : ProxyServer.getInstance().getConfigurationAdapter().getPermissions(group))
            {
                setPermission(permission, false);
            }
        }
    }

    @Override
    @Synchronized(value = "permMutex")
    public boolean hasPermission(String permission)
    {
        Boolean val = permissions.get(permission);
        return (val == null) ? false : val;
    }

    @Override
    @Synchronized(value = "permMutex")
    public void setPermission(String permission, boolean value)
    {
        permissions.put(permission, value);
    }

    private class UpstreamBridge extends Thread
    {

        public UpstreamBridge()
        {
            super("Upstream Bridge - " + name);
        }

        @Override
        public void run()
        {
            while (!socket.isClosed())
            {
                try
                {
                    byte[] packet = in.readPacket();
                    boolean sendPacket = true;
                    int id = Util.getId(packet);

                    switch (id)
                    {
                        case 0x00:
                            if (trackingPingId == new Packet0KeepAlive(packet).id)
                            {
                                int newPing = (int) (System.currentTimeMillis() - pingTime);
                                ProxyServer.getInstance().getTabListHandler().onPingChange(UserConnection.this, newPing);
                                ping = newPing;
                            }
                            break;
                        case 0x03:
                            Packet3Chat chat = new Packet3Chat(packet);
                            if (chat.message.startsWith("/"))
                            {
                                sendPacket = !ProxyServer.getInstance().getPluginManager().dispatchCommand(UserConnection.this, chat.message.substring(1));
                            } else
                            {
                                ChatEvent chatEvent = new ChatEvent(UserConnection.this, server, chat.message);
                                ProxyServer.getInstance().getPluginManager().callEvent(chatEvent);
                                sendPacket = !chatEvent.isCancelled();
                            }
                            break;
                        case 0xFA:
                            // Call the onPluginMessage event
                            PacketFAPluginMessage message = new PacketFAPluginMessage(packet);
                            PluginMessageEvent event = new PluginMessageEvent(UserConnection.this, server, message.tag, message.data);
                            ProxyServer.getInstance().getPluginManager().callEvent(event);

                            if (event.isCancelled())
                            {
                                continue;
                            }
                            break;
                    }

                    while (!server.packetQueue.isEmpty())
                    {
                        DefinedPacket p = server.packetQueue.poll();
                        if (p != null)
                        {
                            server.out.write(p.getPacket());
                        }
                    }

                    EntityMap.rewrite(packet, clientEntityId, serverEntityId);
                    if (sendPacket && !server.socket.isClosed())
                    {
                        server.out.write(packet);
                    }
                } catch (IOException ex)
                {
                    destroySelf("Reached end of stream");
                } catch (Exception ex)
                {
                    destroySelf(Util.exception(ex));
                }
            }
        }
    }

    private class DownstreamBridge extends Thread
    {

        public DownstreamBridge()
        {
            super("Downstream Bridge - " + name);
        }

        @Override
        public void run()
        {
            try
            {
                outer:
                while (!reconnecting)
                {
                    byte[] packet = server.in.readPacket();
                    int id = Util.getId(packet);

                    switch (id)
                    {
                        case 0x00:
                            trackingPingId = new Packet0KeepAlive(packet).id;
                            pingTime = System.currentTimeMillis();
                            break;
                        case 0x03:
                            Packet3Chat chat = new Packet3Chat(packet);
                            ChatEvent chatEvent = new ChatEvent(server, UserConnection.this, chat.message);
                            ProxyServer.getInstance().getPluginManager().callEvent(chatEvent);

                            if (chatEvent.isCancelled())
                            {
                                continue;
                            }
                            break;
                        case 0xC9:
                            PacketC9PlayerListItem playerList = new PacketC9PlayerListItem(packet);
                            if (!ProxyServer.getInstance().getTabListHandler().onListUpdate(UserConnection.this, playerList.username, playerList.online, playerList.ping))
                            {
                                continue;
                            }
                            break;
                        case 0xFA:
                            // Call the onPluginMessage event
                            PacketFAPluginMessage message = new PacketFAPluginMessage(packet);
                            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message.data));
                            PluginMessageEvent event = new PluginMessageEvent(server, UserConnection.this, message.tag, message.data);
                            ProxyServer.getInstance().getPluginManager().callEvent(event);

                            if (event.isCancelled())
                            {
                                continue;
                            }

                            switch (message.tag)
                            {
                                case "BungeeCord::Disconnect":
                                    break outer;
                                case "BungeeCord::Forward":
                                    String target = in.readUTF();
                                    String channel = in.readUTF();
                                    short len = in.readShort();
                                    byte[] data = new byte[len];
                                    in.readFully(data);
                                    break;
                                case "BungeeCord::Connect":
                                    Server server = ProxyServer.getInstance().getServer(in.readUTF());
                                    if (server != null)
                                    {
                                        connect(server);
                                        break outer;
                                    }
                                    break;
                            }
                    }

                    while (!packetQueue.isEmpty())
                    {
                        DefinedPacket p = packetQueue.poll();
                        if (p != null)
                        {
                            out.write(p.getPacket());
                        }
                    }

                    EntityMap.rewrite(packet, serverEntityId, clientEntityId);
                    out.write(packet);
                }
            } catch (Exception ex)
            {
                destroySelf(Util.exception(ex));
            }
        }
    }
}