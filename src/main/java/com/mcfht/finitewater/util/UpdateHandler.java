package com.mcfht.finitewater.util;

import java.util.Map.Entry;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.mcfht.finitewater.fluids.BlockFFluid;
import com.mcfht.finitewater.fluids.BlockSourceD;
import com.mcfht.finitewater.util.ChunkCache.ChunkMap;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.Phase;
import cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent;

public class UpdateHandler {
	public int tickCounter = 0;
	
	public static final UpdateHandler INSTANCE = new UpdateHandler();
	
	/**
	 * Clean up after ourselves when a chunk is unloaded.
	 * @param event
	 */
	@SubscribeEvent
	public void chunkUnload(ChunkEvent.Unload event)
	{
		if (ChunkCache.worldCache.get(event.world) != null)
		{
			if (ChunkCache.worldCache.get(event.world).waterCache.get(event.getChunk()) != null)
			{
				ChunkCache.worldCache.get(event.world).waterCache.get(event.getChunk()).waterArray = null;
				ChunkCache.worldCache.get(event.world).waterCache.get(event.getChunk()).updateFlags = null;
				ChunkCache.worldCache.get(event.world).waterCache.get(event.getChunk()).updateCounter = null;
				ChunkCache.worldCache.get(event.world).waterCache.remove(event.getChunk());
			}
		}
	}
	
	/**
	 * Clean up after ourselves when a world is unloaded
	 * @param event
	 */
	@SubscribeEvent
	public void worldUnload(WorldEvent.Unload event)
	{
		if (ChunkCache.worldCache.get(event.world) != null)
		{
			for (Chunk c : ChunkCache.worldCache.get(event.world).waterCache.keySet())
			{
				ChunkCache.worldCache.get(event.world).waterCache.get(c).waterArray = null;
				ChunkCache.worldCache.get(event.world).waterCache.get(c).updateFlags = null;
				ChunkCache.worldCache.get(event.world).waterCache.get(c).updateCounter = null;
				ChunkCache.worldCache.get(event.world).waterCache.remove(c);
			}
		}
	}
	
	@SubscribeEvent
	public void serverTick(ServerTickEvent event)
	{
		if (event.phase != Phase.END) return;
		
		tickCounter++;
		if ((tickCounter % 5) == 0)
		{
			/*
			 * How it works!
			 * 
			 * 1. Select all players and determine a global number of allowed updates
			 * 2. Iterate over the chunks around the players
			 *    - Try to do all very close chunks
			 *    - Distribute a few random cycles to more distant chunks (hacky implementation but w/e)
			 * 
			 * Within each chunk, we have a boolean map of flags, and a preset counter telling us roughly
			 * how many updates we need to process in this chunk. From here, we just iterate over the boolean
			 * map and update the blocks as we come to them, unflagging them as we go. Additionally, we spread
			 * a few random ticks out to some blocks, to use for our own purposes.
			 * 
			 * Note that the boolean map uses a 16x16x16 grouping. This allows us to simplify calculations,
			 * since who is going to 1. Fill a chunk from top to bottom with water, and 2. Want their code
			 * performing 65536 iteration loops for less than 4000 water blocks.
			 * 
			 * ADVANTAGES:
			 * By using a simple array to store flags, we can greatly increase the speed of flagging updates
			 * (as opposed to maps). By performing all updates at once, we also ditch the overhead cost of
			 * having updates "out of alignment" (aka, block A updates in tick 1, scheduling neighbor blocks,
			 * which get scheduled again in the next tick by block B).
			 * 
			 * DISADVANTAGES:
			 * Potential for jitter... Might investigate threading 
			 * (kudos to mbxr and his "Fysiks is Fun" mod for this idea)
			 */
			
			int tickQuota;
			//Leave a minimum number of ticks per world per player (should cover a couple of chunks)
			//Not sure how well this works with hundreds of players lol
			tickQuota = Math.max(48, 300/Math.max(1, MinecraftServer.getServer().getCurrentPlayerCount()));
			
			for (Object p : MinecraftServer.getServer().getEntityWorld().playerEntities)
			{
				EntityPlayer player = (EntityPlayer) p;
				ChunkMap map = ChunkCache.worldCache.get(player.worldObj);
				if (map == null) continue;
				
				int ticksLeft = tickQuota; //Give ourselves a tick quota
				//iterate over all flagged chunks
				for (Entry<Chunk, ChunkCache> c : map.waterCache.entrySet())
				{
					//Get all the relative coordinates of each chunk for distance testing
					int x = c.getKey().xPosition - (((int)player.posX) >> 4); 
					int z = c.getKey().zPosition - (((int)player.posZ) >> 4); 
					//Update all nearby chunks, along with some occasional random updates in other close chunks
					//Extreme hax lol
					if ((x * x + z * z <= 4*4) || (player.getRNG().nextInt(32) == 0 && (x * x + z * z <= 12*12) ))
					{
						//Iterate over each 
						for (int i = 0; i < 16; i++)
						{
							///////////////////////////////////////////////////////////////////////////////
							//Perform our own update tick on some random blocks
							for (int j = 0; j < 5; j++)
							{
								//Choose a random block in this EBS
								int xx = (c.getKey().xPosition << 4) + player.getRNG().nextInt(16);
								int yy = (i << 4) + player.worldObj.rand.nextInt(16);
								int zz = (c.getKey().zPosition << 4) + player.getRNG().nextInt(16);
								Block b = player.worldObj.getBlock(xx, yy, zz);
								
								//Only bother updating it if it is a fluid
								if (b instanceof BlockFFluid)
								{
									int level = ((BlockFFluid) b).getLevel(player.worldObj, xx, yy, zz);
									if (level < BlockFFluid.maxWater - (BlockFFluid.maxWater >> 3))
									{
										((BlockFFluid) b).equalize(player.worldObj, xx, yy, zz, 8);
									}
									
								}
								
							}
							////////////////////////////////////////////////////////////////////////////////////
							
							//No updates, exit
							if (c.getValue().updateCounter[i] == 0)
								continue;
	
							if (ticksLeft <= 0) //prevent excessive update spamming
								//TODO: Ensure all updates occur eventually
								break;

							//System.out.println(i + " =======> Performing : " + c.getValue().updateCounter[i] + " Block Updates");
							//Use some tick quota, but maintain some minimum number of global updates
							ticksLeft -= Math.max(4, c.getValue().updateCounter[i] >> 8);
							//Reset the counter
							c.getValue().updateCounter[i] = 0;;
							
							/////////////////////////////////////////////////////////////////////////////////////
							for (int j = 0; j < 4096; j++)
							{
								if (c.getValue().updateFlags[i][j])
								{
									//Un-flag this block
									c.getValue().updateFlags[i][j] = false;
							
									int x1 = c.getKey().xPosition << 4;
									int z1 =  c.getKey().zPosition << 4;
									int y1 = i << 4;
																		
									x1 += j & 0xF;
									y1 += (j >> 4) & 0xF;
									z1 += (j >> 8) & 0xF;
									
									Block b = player.worldObj.getBlock(x1, y1, z1);
									
									if (b instanceof BlockFFluid)
									{
										//System.out.println("Ticking...");
										b.updateTick(player.worldObj, x1, y1, z1, player.worldObj.rand);
										player.worldObj.markBlockForUpdate(x1, y1, z1);
									}
								}
							}
							////////////////////////////////////////////////////////////////////////////////////////////
						}
					}
				}
			}
		}
	}
}