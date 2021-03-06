package server;
import common.*;

import java.lang.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;

public class Client implements Runnable
{
	public Socket socket;
	public DataOutputStream out;
	public ByteArrayOutputStream out2;
	private DataInputStream in;
	public int loginStage;
	public WorldMap map;
	public int id, version, dc;
	public boolean isRequestingChunk;
	public int rcX, rcY, rcP;
	public GZIPOutputStream rcin;
	public Server serv;
	public NetSender ns;
	public Player player;
	public long frames = 0;
	public int pingsWaiting = 0;
	private Auth auth;
	public boolean passWait=false;
	public Region region;
	public World world;
	public int copyStage = 0;
	public int protectStage = 0;
	public int unProtectStage = 0;
	public boolean isCopying = false;
	public boolean isPasting = false;
	public boolean isProtecting = false;
	public boolean isUnprotecting = false;
	public int cx,cy,deaths;

	public Client(Socket s, WorldMap m, int iz, Server se)
	{
		try
		{
			socket = s;
			serv = se;
			socket.setTcpNoDelay(true);
			map = m;
			id = iz;
			dc = 0;
			player = new Player(0,0);
			player.health = 5;
			loginStage = 0;
			in = new DataInputStream(socket.getInputStream());
			out2 = new ByteArrayOutputStream(65536);
			out = new DataOutputStream(out2);
			ns = new NetSender(socket.getOutputStream());
			Thread tns = new Thread(ns);
			tns.start();
			region=new Region();
			System.out.println("New user with ID " + id + " connected!");
		}
		catch(Exception e)
		{
			System.out.println("Fatal Server client thread init error!");
			System.exit(1);
		} 
	}
	
	public String getName() { return player.name; }

	public void kill()
	{
		if(!world.isPvP) return;
		player.health--;
		if(player.health<=0)
		{
			player.health = 5;
			deaths++;
			sendChatMsgAll("&c" + getName() + "&c was killed!");
			teleport(world.spawnX,world.spawnY);
		}
		sendHealth(player.health);
	}
	public void resetPvP()
	{
		deaths=0;
	}

	public void playSound(int tx,int ty, int val)
	{
		int ax=player.x-tx;
		int ay=player.y-ty;
		if(ax>=-128 && ax<128 && ay>=-128 && ay<128)
		{
			synchronized(out)
			{
				try
				{
					out.writeByte(NetConstServer.PLAY_SOUND);
					out.writeByte((byte)ax);
					out.writeByte((byte)ay);
					out.writeByte((byte)val);
				}
				catch(Exception e){};
				sendPacket();
			}
		}
	}
	
	public void changeMap(WorldMap newMap)
	{
		try
		{
			map.setPlayer(player.x,player.y,0);
			map.physics.players[id] = null;
			despawnPlayer();
			despawnOthers();
			map=newMap;
			world=serv.findWorld(map.mapName);
			player.move(world.spawnX, world.spawnY);
			map.physics.players[id] = player;
			map.setPlayer(player.x,player.y,1);
			spawnPlayer();
			spawnOthers();
			synchronized(out)
			{
				out.writeByte(NetConstServer.CHANGE_MAP);	
				out.writeInt(player.x);
				out.writeInt(player.y);
				sendPacket();
			}
			setRaycasting(world.isRaycasted);
			setPvP(world.isPvP);
			sendChatMsgSelf("&aMap changed to &f" + newMap.mapName);
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal changeMap error! (i hope)");
			e.printStackTrace();
		}
	}

	public void changeNickname(String newn)
	{
		try
		{
			player.name=newn;
			synchronized(out)
			{
				out.writeByte(NetConstServer.PLAYER_NICKNAME);
				out.writeByte(255);
				writeString(newn);
				sendPacket();
				out.writeByte(NetConstServer.PLAYER_NICKNAME);
				out.writeByte((byte)id);
				writeString(newn);
				serv.sendOthersOnMap(id,getPacket());
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal changeNickname error!");
		}
	}
	
	private void writeChatMsgInternal(String m, byte id) {
		try
		{
			String m2 = m;
			while(m2.length()>40)
			{
				out.writeByte(NetConstServer.CHAT);
				out.writeByte(id);
				writeString(m2.substring(0,40));
				m2=m2.substring(40);
			}
			out.writeByte(NetConstServer.CHAT);
			out.writeByte(id);
			writeString(m2);
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal writeChatMsgInternal error!");
		}
	}
	public void sendChatMsgSelf(String m)
	{
		synchronized(out)
		{
			writeChatMsgInternal(m,(byte)id);
			sendPacket();
		}
	}

	public void sendChatMsgID(String m, int i)
	{
		synchronized(out)
		{
			writeChatMsgInternal(m,(byte)i);
			sendPacket();
		}
	}
	
	public void sendChatMsgAll(String m)
	{
		synchronized(out)
		{
			writeChatMsgInternal(m,(byte)id);
			serv.sendAll(getPacket());
		}
	}
	
	public void kick()
	{
		kick("Kicked!");
	}
	
	public void kick(String msg)
	{
		try
		{
			synchronized(out)
			{
				out.writeByte(NetConstServer.KICK);
				writeString(msg);
				sendPacket();
			}
			Thread.sleep(100);
			disconnect();
			System.out.println("User " + id + " was kicked! (" + msg + ")");
		}
		catch(Exception e)
		{
			System.out.println("Fatal Kick error!");
			disconnect();
		}
	}
	public String readString()
	{
		try
		{
			int la = in.readUnsignedByte();
			byte[] t = new byte[la];
			in.read(t,0,la);
			return new String(t);
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal Server readString error!");
			return "";
		} 
	}
	
	public void teleport(int tx, int ty)
	{
		try
		{
			player.x=tx;
			player.y=ty;
			synchronized(out)
			{
				out.writeByte(NetConstServer.MOVE_ABSOLUTE);
				out.writeByte(255);
				out.writeInt(player.x);
				out.writeInt(player.y);
				sendPacket();
				out.writeByte(NetConstServer.MOVE_ABSOLUTE);
				out.writeByte((byte)id);
				out.writeInt(player.x);
				out.writeInt(player.y);
				serv.sendOthersOnMap(id,getPacket());
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal teleport error!");
			e.printStackTrace();
		}
	}
	
	public void writeString(String s)
	{
		try
		{
			byte[] t = s.getBytes();
			synchronized(out)
			{
				out.writeByte(s.length());
				out.write(t,0,s.length());
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal Server writeString error!");
			try
			{
				synchronized(out){out.writeByte(0x00);}
			}
			catch(Exception ee)
			{
				System.out.println("Fatal Server writeString error!");
				disconnect();
			}
		} 
	}
	
	public void sendPacket()
	{
		sendPacket(getPacket());
	}
	
	public void sendPacket(byte[] t)
	{
		try
		{
			synchronized(ns.packets)
			{
				ns.packets.offer(t);
			}
		}
		catch(Exception e)
		{
			System.out.println("Fatal Server sendPacket(byte[]) error!");
			System.exit(1);
		} 
	}
	
	public byte[] getPacket()
	{
		byte[] t = out2.toByteArray();
		out2.reset();
		return t;
	}

	public void sendOpPacket(int val)
	{
		try
		{
			synchronized(out)
			{
				out.writeByte(NetConstServer.OP);
				out.writeShort((short)val*42);
				sendPacket();
			}
		}
		catch(Exception e){}
	}
	public int abs(int a)
	{
		if(a<0) return -a; else return a;
	}
	public void disconnect()
	{
		System.out.println("User " + id + " has disconnected!");
		dc = 1;
		ns.isRunning=false;
		try
		{
			map.setPlayer(player.x,player.y,0);
			despawnPlayer();
			sendChatMsgAll(getName() + " has left.");
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal Server client thread DC packet send error!");
			e.printStackTrace();
		}
	}
	public void setRaycasting(boolean r)
	{
		try
		{
			synchronized(out)
			{
				if(r) out.writeByte(NetConstServer.RAYCAST_ON);
				else out.writeByte(NetConstServer.RAYCAST_OFF);
				sendPacket();
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal setRaycasting error!");


		}
	}

	public void despawnPlayer()
	{
		try
		{
			synchronized(out)
			{
				out.writeByte(NetConstServer.DESPAWN);
				out.writeByte((byte)id);
				serv.sendOthersOnMap(id,getPacket());
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal despawnPlayer error!");
		}
	}

	private void writeSpawnPacket(Client client) {
		try {
			out.writeByte(NetConstServer.SPAWN);
			out.writeByte(client.id);
			writeString(client.player.name);
			out.writeInt(client.player.x);
			out.writeInt(client.player.y);
			out.writeByte(client.player.chr);
			out.writeByte(client.player.col);
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal writeSpawnPacket error");
		}
	}

	public void spawnPlayer()
	{
		synchronized(out) {
			writeSpawnPacket(this);
			serv.sendOthersOnMap(id,getPacket());
		}
	}
	public void spawnOthers()
	{
		try
		{
			for(int pli=0;pli<255;pli++) // < 255, we don't want 255 (self) in it.
				if(pli != id && serv.clients[pli] != null && serv.clients[pli].dc == 0 && serv.clients[pli].map == map)
					synchronized(out) {
						writeSpawnPacket(serv.clients[pli]);
						sendPacket();
					}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal spawnOthers error");
		}
	}

	public void sendHealth(int h)
	{
		try
		{
			synchronized(out)
			{
				out.writeByte(NetConstServer.HEALTH);
				out.writeByte((byte)h);
				sendPacket();
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal sendHealth error");
		}
	}

	public void setPvP(boolean mpvp)
	{
		try
		{
			synchronized(out)
			{
				out.writeByte(NetConstServer.PVP);
				if(mpvp) out.writeByte(1);
				else out.writeByte(0);
				sendPacket();
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal setPvP error");
		}
	}

	public void despawnOthers()
	{
		try
		{
			for(int pli=0;pli<255;pli++)
			{
				if(pli != id && serv.clients[pli] != null && serv.clients[pli].dc == 0 && serv.clients[pli].map == map)
				{
					synchronized(out)
					{
						out.writeByte(NetConstServer.DESPAWN);
						out.writeByte(serv.clients[pli].id);
						sendPacket();
					}
				}	
			}
		}
		catch(Exception e)
		{
			System.out.println("Non-fatal despawnOthers error");
		}
	}

	public void run()
	{
		try
		{
			while(dc == 0)
			{
				int len = 1;
				int packets = 0;
				while(len>0 && dc==0)
				{
					byte[] buf = new byte[1];
					if(!socket.isConnected() || !socket.isBound() || !ns.isRunning) disconnect();
					else if(in.available() > 0) len = in.read(buf,0,1);
					else len = 0;
					if(len > 0 && packets < 32)
					{
						packets++;
						switch((int)(buf[0]&0xFF))
						{
							case NetConstClient.LOGIN:
								if(loginStage>=1)
								{
									readString();
									in.readInt();
									in.readByte();
									in.readByte();
									break;
								} else {
									loginStage = 1;
									player.name = readString();
									version = in.readInt();
									player.chr = in.readByte();
									player.col = in.readByte();
									player.move(serv.spawnX, serv.spawnY);
									if(serv.anonMode) player.name = "User"+id;
									System.out.println("User " + id + " (IP " + socket.getInetAddress().getHostAddress() + ") connected!");
									if(player.name.length()>20)
									{
										kick("Invalid nickname!");
									}
									else if(version!=Version.getProtocolVersion())
									{
										kick("Invalid protocol/game version!");
									}
									else if(serv.isBanned(socket.getInetAddress().getHostAddress()))
									{
										kick("You're banned!");
									}
									else
									{
										if(serv.isOp(socket.getInetAddress().getHostAddress()))
										{
											player.op=true;
											System.out.println("User " + id + " is an Op!");
										}
										synchronized(out)
										{
											out.writeByte(NetConstServer.LOGIN);
											out.writeInt(player.x);
											out.writeInt(player.y);
											writeString(player.name);
											out.writeShort(player.op?42:0);
	
											sendPacket();
										}
										if(serv.passOn)
										{
											auth = new Auth(serv.pass);
											byte[] ae = auth.encrypt();
											synchronized(out)
											{
												out.writeByte(NetConstServer.ENCRYPTED_DATA);
												out.write(ae,0,32);
												sendPacket();
											}
											passWait=true;
										}
										sendChatMsgAll(getName() + " has joined.");
										setRaycasting(world.isRaycasted);
										map.physics.players[id] = player;
										map.setPlayer(player.x,player.y,1);
										spawnPlayer();
										spawnOthers();
									}
								}
								break;
							case NetConstClient.CHUNK_REQUEST:
								rcX = in.readInt();
								rcY = in.readInt();
								System.out.println("[ID " + id + "] sending chunk " + rcX + "," + rcY);
								byte[] t = new byte[16384];
								int z10;
								synchronized(map.chunks)
								{
									t = map.grabChunk(rcX,rcY).saveByteNet();
									z10 = map.findChunkID(rcX,rcY);
								}
								ByteArrayOutputStream bain = new ByteArrayOutputStream();
								rcin = new GZIPOutputStream(bain);
								rcin.write(t,0,t.length);
								rcin.finish();
								rcin.close();
								byte[] t2 = bain.toByteArray();
								int pl = t2.length;
								int pp = t2.length;
								synchronized(out)
								{
									out.writeByte(NetConstServer.DATA_START);
									out.writeByte(NetConstServer.DATA_TYPE_CHUNK); // type
									out.writeInt(rcX);
									out.writeInt(rcY);
									out.writeInt(t2.length);
									sendPacket();
								}
								while(pl>0)
								{
									int pls = 1000;
									if(pl<pls) pls=pl;
									synchronized(out)
									{
										out.writeByte(NetConstServer.DATA);
										out.writeShort(pls);
										out.write(t2,pp-pl,pls);
										sendPacket();
										Thread.sleep(1);
									}
									pl -= pls;
								}
								synchronized(map.chunks) { if(map.chunks[z10].xpos == rcX && map.chunks[z10].ypos == rcY) map.chunks[z10].isUsed=false; }
								synchronized(out)
								{
									out.writeByte(NetConstServer.DATA_END);
									sendPacket();
								}
								break;
							case NetConstClient.MOVE_DELTA:
								byte[] ta = new byte[4];
								ta[0] = NetConstServer.MOVE_DELTA;
								ta[1] = (byte)id;
								ta[2] = in.readByte();
								ta[3] = in.readByte();
								if(passWait) break;
								if(abs(ta[2])>4 || abs(ta[3])>4)
								{
									kick("Invalid movement!");
								}
								serv.sendOthersOnMap(id,ta,4);
								map.setPlayer(player.x,player.y,0);
								player.x+=ta[2];
								player.y+=ta[3];
								map.setPlayer(player.x,player.y,1);
								break;
							case NetConstClient.RESPAWN:
								if(world.isPvP) break;
								map.setPlayer(player.x,player.y,0);
								if(map==serv.map)
								{
									map.setPlayer(serv.spawnX,serv.spawnY,1);
									teleport(serv.spawnX,serv.spawnY);
								} else {
									map.setPlayer(world.spawnX,world.spawnY,1);
									teleport(world.spawnX,world.spawnY);
								}
								break;
							case NetConstClient.MOVE_ABSOLUTE:
								int x29 = in.readInt();
								int y29 = in.readInt();
								if(passWait) break;
								if(abs(x29-player.x)<=16 || abs(y29-player.y)<=16)
								{
									player.x=x29;
									player.y=y29;
									synchronized(out)
									{
										out.writeByte(NetConstServer.MOVE_ABSOLUTE);
										out.writeByte(id);
										out.writeInt(x29);
										out.writeInt(y29);
										serv.sendOthersOnMap(id,getPacket());
									}
								}
								break;
							case NetConstClient.DISCONNECT:
								disconnect();
								break;
							case NetConstClient.MOVE_COMPRESSED:
							case 0x2D:
							case 0x2E:
							case 0x2F:
								int dir2f = buf[0]&0x03;
								int dx2f = map.xMovement[dir2f];
								int dy2f = map.yMovement[dir2f];
								byte[] ta2f = new byte[4];
								ta2f[0] = buf[0];
								ta2f[1] = (byte)id;
								if(passWait) break;
								serv.sendOthersOnMap(id,ta2f,2);
								map.setPlayer(player.x,player.y,0);
								player.x+=dx2f;
								player.y+=dy2f;
								map.setPlayer(player.x,player.y,1);
								break;
							case NetConstClient.PUT_BLOCK:
								int ax = in.readInt();
								int ay = in.readInt();
								byte at = in.readByte();
								byte ach = in.readByte();
								byte aco = in.readByte();
								if(passWait) break;
								byte[] zc = map.getBlock(ax,ay).getBlockData();
								if((player.isOp() && (isCopying || isPasting || isProtecting || isUnprotecting)) || (serv.mapLock && !player.isOp()))
								{
									out.writeByte(NetConstServer.PLACE_BLOCK_PLAYER);
									out.writeByte((byte)id);
									out.writeInt(ax);
									out.writeInt(ay);
									out.writeByte(zc[0]);
									out.writeByte(zc[2]);
									out.writeByte(zc[3]);
									sendPacket();
									if(isCopying)
									{
										if(copyStage == 0)
										{
											sendChatMsgSelf("Click on the other corner.");
											cx=ax;
											cy=ay;
											copyStage++;
										}
										else if(copyStage == 1)
										{
											isCopying=false;
											int tcx = ax;
											int tcy = ay;
											int ts = 0;
											if(tcx<cx) { ts=tcx;tcx=cx;cx=ts; }
											if(tcy<cy) { ts=tcy;tcy=cy;cy=ts; }
											int tcxs = abs(tcx-cx)+1;
											int tcys = abs(tcy-cy)+1;
											if(tcxs>0 && tcxs<=128 && tcys>0 && tcys<=128)
											{
												region.copy(map,cx,cy,tcxs,tcys);
												sendChatMsgSelf("Copied.");
											} else sendChatMsgSelf("Copy error: Invalid size (" + tcxs + ", " + tcys + ")");
										}
									}
									else if(isPasting)
									{
										region.paste(map,ax,ay);
										sendChatMsgSelf("Pasted.");
										System.out.println("[ID " + id + "] Pasted at " + ax + "," + ay + "!");
										isPasting=false;
									}
									
									else if (isProtecting)
									{
										int tcx = ax;
										int tcy = ay;
										int ts = 0;
										if(tcx<cx) { ts=tcx;tcx=cx;cx=ts; }
										if(tcy<cy) { ts=tcy;tcy=cy;cy=ts; }
										int tcxs = abs(tcx-cx)+1;
										int tcys = abs(tcy-cy)+1;
									
										if (protectStage == 0)
										{
											sendChatMsgSelf("Click on the other corner.");
											cx=ax;
											cy=ay;
											protectStage++;
										}
										
										else if (protectStage == 1)
										{
											isProtecting = false;
											world.setProtected(new Rectangle(cx,cy,tcxs,tcys), true);
											sendChatMsgSelf("Protected.");
										}
											else 
											    sendChatMsgSelf("Unprotect error: Invalid size (" + tcxs + ", " + tcys + ")");	
										
									}
									
									else if (isUnprotecting)
									{
										int tcx = ax;
										int tcy = ay;
										int ts = 0;
										if(tcx<cx) { ts=tcx;tcx=cx;cx=ts; }
										if(tcy<cy) { ts=tcy;tcy=cy;cy=ts; }
										int tcxs = abs(tcx-cx)+1;
										int tcys = abs(tcy-cy)+1;
									
										if (unProtectStage == 0)
										{
											sendChatMsgSelf("Click on the other corner.");
											cx=ax;
											cy=ay;
											unProtectStage++;
										}
										
										else if (unProtectStage == 1)
										{
											isUnprotecting = false;
											world.setProtected(new Rectangle(cx,cy,tcxs,tcys), false);
											sendChatMsgSelf("Unprotected.");
										}
											else 
											    sendChatMsgSelf("Unprotect error: Invalid size (" + tcxs + ", " + tcys + ")");	
										
									}
								}
								else if (!world.isProtected(ax, ay))
								{
									int tat = (int)(at&0xFF);
									if(!Block.isPlaceable(tat))
									{
										kick("Invalid block type!");
									}
									byte[] t33 = new byte[4];
									t33[0] = at;
									t33[3] = aco;
									t33[2] = ach;
									t33[1] = (byte)Block.getParam(tat);
									while(map.maplock) { try{ Thread.sleep(1); } catch(Exception e) {} }
									map.modlock=true;
	 								synchronized(map)
	 								{
	 									if(at == -1)
	 									{
	 										map.setPushable(ax,ay,ach,aco);
	 									} else {
	 										map.setBlock(ax,ay,t33[0],t33[1],t33[2],t33[3]);
	 										map.setPushable(ax,ay,(byte)0,(byte)0);
	 									}
	 								}
									map.physics.addBlockToCheck(new Point(ax,ay));
									for(int i=0;i<4;i++)
									{
										map.physics.addBlockToCheck(new Point(ax+map.xMovement[i],ay+map.yMovement[i]));
									}
									Block outBlock = map.getBlock(ax,ay);
									synchronized(out)
									{
										out.writeByte(NetConstServer.PLACE_BLOCK_PLAYER);
										out.writeByte((byte)id);
										out.writeInt(ax);
										out.writeInt(ay);
										out.writeByte(t33[0]);
										out.writeByte(t33[2]);
										out.writeByte(t33[3]);
										if(outBlock.isUpdated() && outBlock.isSent()) {
											serv.sendAllOnMap(id, getPacket()); // Wirium reappear fix
										} else {
											serv.sendOthersOnMap(id,getPacket());
										}
	 									if(at != -1 && zc[5] != 0)
	 									{
	 										out.writeByte(NetConstServer.PLACE_BLOCK_PLAYER);
	 										out.writeByte((byte)id);
	 										out.writeInt(ax);
	 										out.writeInt(ay);
	 										out.writeByte(-1);
	 										out.writeByte(0);
	 										out.writeByte(0);
	 										serv.sendOthersOnMap(id,getPacket());
	 									}
									}
									map.modlock=false;
								} else
								{
									out.writeByte(NetConstServer.PLACE_BLOCK_PLAYER);
									out.writeByte((byte)id);
									out.writeInt(ax);
									out.writeInt(ay);
									out.writeByte(zc[0]);
									out.writeByte(zc[2]);
									out.writeByte(zc[3]);
									sendPacket();
								}
								break;
							case NetConstClient.CHAT:
								String al = readString();
								System.out.println("<" + getName() + "> " + al);
								String alt = serv.parseMessage(al,id);
								if(alt.equals("$N") && !al.equals("")) sendChatMsgAll("<" + getName() + "> " + al);
								else if (!alt.equals("")) sendChatMsgSelf(alt);
								break;
							case NetConstClient.DECRYPTED_DATA:
								byte[] t51 = new byte[32];
								in.read(t51,0,32);
								if(!auth.testDecrypt(t51))
								{
									kick("Incorrect password!");
								}
								else passWait=false;
 								break;
							case NetConstClient.SHOOT:
								int xb = in.readInt();
								int yb = in.readInt();
								byte bt = in.readByte();
								map.setBullet(xb,yb,bt);
								map.setBulletNet(xb,yb,bt);
								map.physics.addBlockToCheck(new Point(xb,yb));
								for(int i=0;i<4;i++) map.physics.addBlockToCheck(new Point(xb+map.xMovement[i],yb+map.yMovement[i]));
								break;
 							case NetConstClient.PUSH:
 								{
 									int lolx = player.x;
 									int loly = player.y;
 									int lolvx = in.readByte();
 									int lolvy = in.readByte();

 									if((lolvx != 0 && lolvy != 0) || (lolvx == 0 && lolvy == 0))
 										kick("Invalid touch distance!");
 									else if(lolvx < -1 || lolvx > 1 || lolvy < -1 || lolvy > 1)
 										kick("Invalid touch distance!");
 									else {
 										byte[] dq;
 										boolean pa;
 										synchronized(map)
 										{
 											dq = map.getBlock(lolx+lolvx,loly+lolvy).getBlockData();
 											pa = map.pushAttempt(lolx+lolvx,loly+lolvy,lolvx,lolvy);
 										}
 										if(pa)
 										{
 											map.setPlayer(player.x,player.y,0);
 											player.x = lolx+lolvx;
 											player.y = loly+lolvy;
 											synchronized(out)
 											{
 												out.writeByte(NetConstServer.PLACE_PUSHABLE_PLAYER);
 												out.writeByte(255);
 												out.writeInt(lolx+lolvx);
 												out.writeInt(loly+lolvy);
 												out.writeByte((byte)lolvx);
 												out.writeByte((byte)lolvy);
 												out.writeByte(dq[4]);
 												out.writeByte(dq[5]);
 												sendPacket();
 												out.writeByte(NetConstServer.PLACE_PUSHABLE_PLAYER);
 												out.writeByte(id);
 												out.writeInt(lolx+lolvx);
 												out.writeInt(loly+lolvy);
 												out.writeByte((byte)lolvx);
 												out.writeByte((byte)lolvy);
 												out.writeByte(dq[4]);
 												out.writeByte(dq[5]);

 												serv.sendOthersOnMap(id,getPacket());
 												map.setPlayer(player.x,player.y,1);
 												map.setPlayer(player.x+lolvx,player.y+lolvy,1);
 											}
 										}
 									}
 								}
								break;
							case NetConstClient.PING:
								synchronized(out)
								{
									out.writeByte(NetConstServer.PONG);
									sendPacket();
								}
								break;
							case NetConstClient.PONG:
								pingsWaiting--;
								break;
							default:
								System.out.println("Unknown packet " + (int)(buf[0]&0xFF) + "!");
								break; // Ignore.
						}
					}
				}
				Thread.sleep(5);
				frames++;
				if(frames%625==0) // every 10 seconds
				{
					synchronized(out)
					{
						out.writeByte(NetConstServer.PING);
						sendPacket();
					}
					pingsWaiting++;
					if(pingsWaiting>10) disconnect();
				}
			}
		}
		catch(Exception e)
		{
			System.out.println("Fatal Server client thread loop error!");
			e.printStackTrace();
			dc = 1;
			ns.isRunning=false;
		}
	}
}
