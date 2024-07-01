package dev.nonamecrackers2.simpleclouds.client.mesh;

import java.io.IOException;
import java.util.List;
import java.util.Queue;

import javax.annotation.Nullable;

import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Queues;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.MemoryTracker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.shader.SimpleCloudsShaders;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ShaderStorageBufferObject;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

public abstract class CloudMeshGenerator implements AutoCloseable
{
	public static final int MAX_NOISE_LAYERS = 4;
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/CloudMeshGenerator");
	public static final CloudMeshGenerator.LevelOfDetailConfig[] LEVEL_OF_DETAIL = new CloudMeshGenerator.LevelOfDetailConfig[] {
		new CloudMeshGenerator.LevelOfDetailConfig(2, 4),
		new CloudMeshGenerator.LevelOfDetailConfig(4, 3),
		new CloudMeshGenerator.LevelOfDetailConfig(8, 2)
	};
	public static final int PRIMARY_CHUNK_COUNT;
	private static final List<CloudMeshGenerator.PreparedChunk> PREPARED_CHUNKS;
	public static final ResourceLocation MAIN_CUBE_MESH_GENERATOR = SimpleCloudsMod.id("cube_mesh");
	public static final int EFFECTIVE_CHUNK_SPAN; //The total span of the complete renderable area, including all level of detail layers
	public static final int PRIMARY_CHUNK_SPAN = 8; //The total span of the primary, full detail cloud area
	public static final int VERTICAL_CHUNK_SPAN = 8;
	public static final int WORK_SIZE = 4;
	public static final int LOCAL_SIZE = 8;
	public static final int SIDE_BUFFER_SIZE = 368435456;
	public static final int ELEMENTS_PER_VERTEX = 7;
	public static final int BYTES_PER_VERTEX = 4 * ELEMENTS_PER_VERTEX;
	public static final int BYTES_PER_SIDE = BYTES_PER_VERTEX * 4;
	public static final int INDEX_BUFFER_SIZE = 107108864;
	protected final ResourceLocation meshShaderLoc;
	protected final int meshGenInterval;
	protected final Queue<Runnable> chunkGenTasks = Queues.newArrayDeque();
	protected int tasksPerTick;
	protected @Nullable ComputeShader shader;
	protected float scrollX;
	protected float scrollY;
	protected float scrollZ;
	protected int arrayObjectId = -1;
	protected int vertexBufferId = -1;
	protected int indexBufferId = -1;
	protected int totalIndices;
	protected int totalSides;
	protected boolean testFacesFacingAway;
	private double currentCamX;
	private double currentCamY;
	private double currentCamZ;
	private float currentScale;
	
	static
	{
		int radius = PRIMARY_CHUNK_SPAN / 2;
		for (CloudMeshGenerator.LevelOfDetailConfig config : LEVEL_OF_DETAIL)
			radius += config.chunkScale() * config.spread();
		EFFECTIVE_CHUNK_SPAN = radius * 2;
		var result = prepareChunks();
		PREPARED_CHUNKS = result.getRight();
		PRIMARY_CHUNK_COUNT = result.getLeft();
	}
	
	private static Pair<Integer, List<CloudMeshGenerator.PreparedChunk>> prepareChunks()
	{
		ImmutableList.Builder<CloudMeshGenerator.PreparedChunk> builder = ImmutableList.builder();
		
		int currentRadius = PRIMARY_CHUNK_SPAN / 2;
		int primaryChunkCount = 0;
		for (int r = 0; r <= currentRadius; r++)
		{
			for (int y = 0; y < VERTICAL_CHUNK_SPAN; y++)
			{
				for (int x = -r; x < r; x++)
				{
					builder.add(new CloudMeshGenerator.PreparedChunk(0, 1, x, y, -r, -1));
					builder.add(new CloudMeshGenerator.PreparedChunk(0, 1, x, y, r - 1, -1));
					primaryChunkCount += 2;
				}
				for (int z = -r + 1; z < r - 1; z++)
				{
					builder.add(new CloudMeshGenerator.PreparedChunk(0, 1, -r, y, z, -1));
					builder.add(new CloudMeshGenerator.PreparedChunk(0, 1, r - 1, y, z, -1));
					primaryChunkCount += 2;
				}
			}
		}
		
		for (int i = 0; i < LEVEL_OF_DETAIL.length; i++)
		{
			CloudMeshGenerator.LevelOfDetailConfig config = LEVEL_OF_DETAIL[i];
			int chunkCount = 0;
			int lodLevel = i + 1;
			for (int deltaR = 1; deltaR <= config.spread(); deltaR++)
			{
				int ySpan = Mth.ceil((float)VERTICAL_CHUNK_SPAN / (float)config.chunkScale());
				boolean noOcclusion = deltaR == 1;
				for (int y = 0; y < ySpan; y++)
				{
					int r = currentRadius / config.chunkScale() + deltaR;
					for (int x = -r; x < r; x++)
					{
						builder.add(new CloudMeshGenerator.PreparedChunk(lodLevel, config.chunkScale(), x, y, -r, noOcclusion ? 5 : -1));
						builder.add(new CloudMeshGenerator.PreparedChunk(lodLevel, config.chunkScale(), x, y, r - 1, noOcclusion ? 4 : -1));
						chunkCount += 2;
					}
					for (int z = -r + 1; z < r - 1; z++)
					{
						builder.add(new CloudMeshGenerator.PreparedChunk(lodLevel, config.chunkScale(), -r, y, z, noOcclusion ? 1 : -1));
						builder.add(new CloudMeshGenerator.PreparedChunk(lodLevel, config.chunkScale(), r - 1, y, z, noOcclusion ? 0 : -1));
						chunkCount += 2;
					}
				}
			}
			currentRadius = currentRadius + config.spread() * config.chunkScale();
			config.setChunkCount(chunkCount);
		}
		
		return Pair.of(primaryChunkCount, builder.build());
	}
	
	public CloudMeshGenerator(ResourceLocation meshShaderLoc, int meshGenInterval)
	{
		this.meshShaderLoc = meshShaderLoc;
		if (meshGenInterval <= 0)
			throw new IllegalArgumentException("Please input a mesh gen interval greater than 0");
		this.meshGenInterval = meshGenInterval;
	}
	
	public CloudMeshGenerator setTestFacesFacingAway(boolean flag)
	{
		this.testFacesFacingAway = flag;
		return this;
	}
	
	public static int getCloudAreaMaxRadius()
	{
		return EFFECTIVE_CHUNK_SPAN * WORK_SIZE * LOCAL_SIZE / 2;
	}
	
	@Override
	public void close()
	{
		if (this.shader != null)
			this.shader.close();
		this.shader = null;
		
		if (this.arrayObjectId >= 0)
		{
			RenderSystem.glDeleteVertexArrays(this.arrayObjectId);
			this.arrayObjectId = -1;
		}
		
		if (this.vertexBufferId >= 0)
		{
			RenderSystem.glDeleteBuffers(this.vertexBufferId);
			this.vertexBufferId = -1;
		}
		
		if (this.indexBufferId >= 0)
		{
			RenderSystem.glDeleteBuffers(this.indexBufferId);
			this.indexBufferId = -1;
		}
	}
	
	protected ComputeShader createShader(ResourceManager manager) throws IOException
	{
		return ComputeShader.loadShader(this.meshShaderLoc, manager, LOCAL_SIZE, LOCAL_SIZE, LOCAL_SIZE);
	}
	
	protected void setupShader()
	{
		ShaderStorageBufferObject buffer = this.shader.bindShaderStorageBuffer("Counter", GL15.GL_DYNAMIC_DRAW);
		buffer.allocateBuffer(4);
		buffer.writeData(b -> {
			b.putInt(0, 0);
		});
		this.shader.bindShaderStorageBuffer("SideDataBuffer", GL15.GL_DYNAMIC_DRAW).allocateBuffer(SIDE_BUFFER_SIZE); //Vertex data, arbitrary size
		this.shader.bindShaderStorageBuffer("IndexBuffer", GL15.GL_DYNAMIC_DRAW).allocateBuffer(INDEX_BUFFER_SIZE); //Index data, arbitrary size
	}
	
	public void init(ResourceManager manager)
	{
		RenderSystem.assertOnRenderThreadOrInit();
		
		if (this.arrayObjectId >= 0)
		{
			RenderSystem.glDeleteVertexArrays(this.arrayObjectId);
			this.arrayObjectId = -1;
		}
		
		if (this.vertexBufferId >= 0)
		{
			RenderSystem.glDeleteBuffers(this.vertexBufferId);
			this.vertexBufferId = -1;
		}
		
		if (this.indexBufferId >= 0)
		{
			RenderSystem.glDeleteBuffers(this.indexBufferId);
			this.indexBufferId = -1;
		}
		
		this.arrayObjectId = GL30.glGenVertexArrays();
		this.vertexBufferId = GL15.glGenBuffers();
		this.indexBufferId = GL15.glGenBuffers();
		
		GL30.glBindVertexArray(this.arrayObjectId);
		
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vertexBufferId);
		GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER, MemoryTracker.create(SIDE_BUFFER_SIZE), GL15.GL_DYNAMIC_DRAW);
		//Vertex position
		GL20.glEnableVertexAttribArray(0);
		GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, 0);
		//Vertex color
		GL20.glEnableVertexAttribArray(1);
		GL20.glVertexAttribPointer(1, 1, GL11.GL_FLOAT, true, 28, 12);
		//Vertex normal
		GL20.glEnableVertexAttribArray(2);
		GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, true, 28, 16);
		
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.indexBufferId);
		GlStateManager._glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, MemoryTracker.create(INDEX_BUFFER_SIZE), GL15.GL_DYNAMIC_DRAW);
		
		GL30.glBindVertexArray(0);
		
		if (this.shader != null)
		{
			this.shader.close();
			this.shader = null;
		}
		
		try
		{
			this.shader = this.createShader(manager);
			this.setupShader();
			//this.generateMesh(0.0D, 0.0D, 0.0D, 1.0F, null);
		}
		catch (IOException e)
		{
			LOGGER.warn("Failed to load compute shader", e);
		}
	}
	
//	protected void rebindBuffers()
//	{
//		if (this.arrayObjectId != -1)
//		{
//			MutableInt totalSides = new MutableInt();
//			this.shader.getShaderStorageBuffer("Counter").readData(b -> {
//				totalSides.setValue(b.getInt(0));
//			});
//			this.totalSides = totalSides.getValue();
//			this.totalIndices = this.totalSides * 6;
//			int vertexBufferId = this.shader.getShaderStorageBuffer("SideDataBuffer").getId();
//			int indexBufferId = this.shader.getShaderStorageBuffer("IndexBuffer").getId();
//			
//			GL30.glBindVertexArray(this.arrayObjectId);
//			
//			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBufferId);
//			//Vertex position
//			GL20.glEnableVertexAttribArray(0);
//			GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, 0);
//			//Vertex color
//			GL20.glEnableVertexAttribArray(1);
//			GL20.glVertexAttribPointer(1, 1, GL11.GL_FLOAT, true, 28, 12);
//			//Vertex normal
//			GL20.glEnableVertexAttribArray(2);
//			GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, true, 28, 16);
//			
//			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
//			
//			GL30.glBindVertexArray(0);
//		}
//	}
	
	protected void copyDataOver()
	{
		if (this.vertexBufferId != -1 && this.indexBufferId != -1 && this.shader != null && this.shader.isValid())
		{
			MutableInt totalSides = new MutableInt();
			this.shader.getShaderStorageBuffer("Counter").readData(b -> {
				totalSides.setValue(b.getInt(0));
			});
			this.totalSides = totalSides.getValue();
			this.totalIndices = this.totalSides * 6;
			
			if (this.totalSides > 0)
			{
				int vertexBufferId = this.shader.getShaderStorageBuffer("SideDataBuffer").getId();
				int indexBufferId = this.shader.getShaderStorageBuffer("IndexBuffer").getId();
				GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, vertexBufferId);
				GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.vertexBufferId);
				GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, this.totalSides * BYTES_PER_SIDE);
				GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, indexBufferId);
				GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.indexBufferId);
				GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, this.totalIndices * 4);
				GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
				GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
			}
		}
	}
	
	public void setScroll(float x, float y, float z)
	{
		this.scrollX = x;
		this.scrollY = y;
		this.scrollZ = z;
	}
	
	protected void generateChunk(int lodLevel, int lodScale, int x, int y, int z, float offsetX, float offsetY, float offsetZ, float scale, float camOffsetX, float camOffsetZ, int noOcclusionDirectionIndex)
	{
		this.shader.forUniform("LodLevel", loc -> {
			GL20.glUniform1i(loc, lodLevel);
		});
		this.shader.forUniform("RenderOffset", loc -> {
			GL20.glUniform3f(loc, offsetX / scale + camOffsetX / scale, offsetY / scale, offsetZ / scale + camOffsetZ / scale);
		});
		this.shader.forUniform("Scale", loc -> {
			GL20.glUniform1f(loc, lodScale);
		});
		this.shader.forUniform("DoNotOccludeSide", loc -> {
			GL20.glUniform1i(loc, noOcclusionDirectionIndex);
		});
		this.shader.dispatch(WORK_SIZE, WORK_SIZE, WORK_SIZE, false);
	}
	
	protected void doMeshGenning(double camX, double camY, double camZ, float scale)
	{
		this.shader.forUniform("Scroll", loc -> {
			GL20.glUniform3f(loc, this.scrollX, this.scrollY, this.scrollZ);
		});
		this.shader.forUniform("Origin", loc -> {
			GL20.glUniform3f(loc, (float)camX / scale, (float)camY / scale, (float)camZ / scale);
		});
		this.shader.forUniform("TestFacesFacingAway", loc -> {
			GL20.glUniform1i(loc, this.testFacesFacingAway ? 1 : 0);
		});	
		
		for (int i = 0; i < this.tasksPerTick; i++)
		{
			Runnable task = this.chunkGenTasks.poll();
			if (task != null)
				task.run();
			else
				break;
		}
	}
	
	public boolean tick(double camX, double camY, double camZ, float scale, @Nullable Frustum frustum)
	{
		if (this.shader == null || !this.shader.isValid())
			return false;
		
		if (this.chunkGenTasks.isEmpty())
		{
			this.shader.getShaderStorageBuffer("Counter").writeData(b -> {
				b.putInt(0, 0);
			});
			this.populateChunkGenTasks(camX, camY, camZ, scale, frustum);
			this.currentCamX = camX;
			this.currentCamY = camY;
			this.currentCamZ = camZ;
			this.currentScale = scale;
		}

		if (!this.chunkGenTasks.isEmpty())
			this.doMeshGenning(this.currentCamX, this.currentCamY, this.currentCamZ, this.currentScale);
		
		if (this.chunkGenTasks.isEmpty())
		{
			this.copyDataOver();
			return false;
		}
		else
		{
			return true;
		}
	}
	
	protected void populateChunkGenTasks(double camX, double camY, double camZ, float scale, @Nullable Frustum frustum)
	{
		int chunkCount = 0;
		float chunkSizeUpscaled = 32.0F * scale;
		float globalOffsetX = ((float)Mth.floor(camX / chunkSizeUpscaled) * chunkSizeUpscaled);
		float globalOffsetZ = ((float)Mth.floor(camZ / chunkSizeUpscaled) * chunkSizeUpscaled);
		for (CloudMeshGenerator.PreparedChunk chunk : PREPARED_CHUNKS)
		{
			if (chunk.checkIfVisibleAndQueue(this, camX, camY, camZ, scale, globalOffsetX, globalOffsetZ, frustum))
				chunkCount++;
		}
		this.tasksPerTick = Mth.ceil((float)chunkCount / (float)this.meshGenInterval);
	}
	
	public void render(PoseStack stack, Matrix4f projMat, float partialTick, float r, float g, float b)
	{
		RenderSystem.assertOnRenderThread();
		if (this.arrayObjectId != -1)
		{
			BufferUploader.reset();
			
			RenderSystem.disableBlend();
			RenderSystem.enableDepthTest();
			RenderSystem.setShaderColor(r, g, b, 1.0F);
			
			GL30.glBindVertexArray(this.arrayObjectId);
			
			RenderSystem.setShader(SimpleCloudsShaders::getCloudsShader);
			SimpleCloudsRenderer.prepareShader(RenderSystem.getShader(), stack.last().pose(), projMat);
			RenderSystem.getShader().apply();
			RenderSystem.drawElements(GL11.GL_TRIANGLES, this.totalIndices, GL11.GL_UNSIGNED_INT);
			RenderSystem.getShader().clear();
			
			GL30.glBindVertexArray(0);
			
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		}
	}
	
	public int getArrayObjectId()
	{
		return this.arrayObjectId;
	}
	
	public int getTotalIndices()
	{
		return this.totalIndices;
	}
	
	public int getTotalSides()
	{
		return this.totalSides;
	}
	
	public static class LevelOfDetailConfig
	{
		private final int chunkScale;
		private final int spread;
		private int chunkCount;
		
		private LevelOfDetailConfig(int chunkScale, int spread)
		{
			this.chunkScale = chunkScale;
			this.spread = spread;
		}
		
		private void setChunkCount(int count)
		{
			this.chunkCount = count;
		}
		
		public int chunkScale()
		{
			return this.chunkScale;
		}
		
		public int spread()
		{
			return this.spread;
		}
		
		public int chunkCount()
		{
			return this.chunkCount;
		}
	}
	
	static class PreparedChunk
	{
		final int lodLevel;
		final int lodScale;
		final int x;
		final int y;
		final int z;
		final int noOcclusionDirectionIndex;
		
		PreparedChunk(int lodLevel, int lodScale, int x, int y, int z, int noOcclusionDirectionIndex)
		{
			this.lodLevel = lodLevel;
			this.lodScale = lodScale;
			this.x = x;
			this.y = y;
			this.z = z;
			this.noOcclusionDirectionIndex = noOcclusionDirectionIndex;
		}
		
		boolean checkIfVisibleAndQueue(CloudMeshGenerator generator, double camX, double camY, double camZ, float scale, float globalOffsetX, float globalOffsetZ, @Nullable Frustum frustum)
		{
			float chunkSizeLod = 32.0F * scale * this.lodScale;
			float offsetX = (float)this.x * chunkSizeLod;
			float offsetY = (float)this.y * chunkSizeLod;
			float offsetZ = (float)this.z * chunkSizeLod;
			if (frustum == null || frustum.isVisible(new AABB(offsetX, offsetY + 1000.0F, offsetZ, offsetX + chunkSizeLod, offsetY - 1000.0F, offsetZ + chunkSizeLod).move(globalOffsetX, 0.0F, globalOffsetZ).move(-camX, -camY, -camZ)))
			{
				generator.chunkGenTasks.add(() -> generator.generateChunk(this.lodLevel, this.lodScale, this.x, this.y, this.z, offsetX, offsetY, offsetZ, scale, globalOffsetX, globalOffsetZ, this.noOcclusionDirectionIndex));
				return true;
			}
			return false;
		}
	}
}