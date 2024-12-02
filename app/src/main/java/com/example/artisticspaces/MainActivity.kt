package com.example.artisticspaces

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {

    private lateinit var filamentEngine: Engine
    private lateinit var scene: Scene
    private lateinit var assetLoader: AssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Filament Engine
        filamentEngine = Engine.create()

        // Create the Scene using Filament Engine
        scene = filamentEngine.createScene()

        // Create AssetLoader using the Engine and EntityManager
        val entityManager = EntityManager.get()
        assetLoader = AssetLoader(filamentEngine, UbershaderProvider(filamentEngine), entityManager)

        setContent {
            ARPlacementApp()
        }
    }

    @Composable
    fun ARPlacementApp() {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Composable
    fun ARSceneView() {
        // Assuming filamentEngine is already initialized, and camera and scene are set up
        lateinit var filamentEngine: Engine
        lateinit var renderer: Renderer
        lateinit var view: View
        lateinit var camera: Camera
        lateinit var scene: Scene
        lateinit var skybox: Skybox
        lateinit var arSession: Session
        lateinit var arFrame: Frame
        lateinit var assetLoader: AssetLoader

        lateinit var surface: Surface
        lateinit var swapChain: SwapChain

        // Use AndroidView to embed GLSurfaceView in Compose
        AndroidView(
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2) // Set OpenGL ES 2.0
                    setRenderer(object : GLSurfaceView.Renderer {
                        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                            Toast.makeText(context, "Surface Created", Toast.LENGTH_SHORT).show()

                            // Initialize Filament Engine and Resources
                            filamentEngine = Engine.create() // Initialize the Filament engine
                            renderer = filamentEngine.createRenderer() // Create a renderer
                            view = filamentEngine.createView() // Create a view for the scene
                            scene = filamentEngine.createScene() // Create a scene

                            // Initialize Camera
                            camera = filamentEngine.createCamera(0) // Create a camera
                            view.camera = camera // Attach the camera to the view

                            // Add a Skybox for better visuals
                            skybox = Skybox.Builder()
                                .color(0.1f, 0.1f, 0.1f, 1.0f) // Dark background
                                .build(filamentEngine)
                            scene.skybox = skybox

                            // Setup ARCore interaction (plane detection and model placement)
                            arSession = Session(context)
                            arFrame = arSession.update() // Start AR session and get initial frame

                            val entityManager = EntityManager.get()
                            val materialProvider = UbershaderProvider(filamentEngine)
                            assetLoader = AssetLoader(filamentEngine, materialProvider, entityManager)

                            // Load the GLB Model
                            val asset: FilamentAsset? = context.assets.open("src/models/cube.glb").use { inputStream ->
                                val size = inputStream.available()
                                val byteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                                val bytes = ByteArray(size)
                                inputStream.read(bytes)
                                byteBuffer.put(bytes).flip()
                                assetLoader.createAsset(byteBuffer)
                            }

                            // Check if the asset was loaded successfully
                            if (asset != null) {
                                // Add entities from the model to the scene
                                scene.addEntities(asset.entities)

                                // Load resources like textures
                                val resourceLoader = ResourceLoader(filamentEngine)
                                resourceLoader.loadResources(asset)

                                // Mark the asset ready for rendering
                                resourceLoader.destroy()
                            } else {
                                Toast.makeText(context, "Failed to load model", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                            val aspectRatio = width.toDouble() / height.toDouble()

                            // Setup Camera Projection
                            camera.setProjection(
                                45.0, // Field of View
                                aspectRatio, // Aspect Ratio
                                0.1, // Near Plane
                                100.0, // Far Plane
                                Camera.Fov.VERTICAL // Projection mode
                            )

                            // Set the viewport size for rendering
                            val viewport = Viewport(0, 0, width, height)
                            view.setViewport(viewport)

                            // Create a Surface from the GLSurfaceView
                            val surfaceView = findViewById<GLSurfaceView>(0)
                            surface = surfaceView.holder.surface

                            // Create a SwapChain using the Surface
                            swapChain = filamentEngine.createSwapChain(surface)
                        }

                        override fun onDrawFrame(gl: GL10?) {
                            // Update ARCore session
                            arFrame = arSession.update()
                            val updatedPlanes = arFrame.getUpdatedTrackables(Plane::class.java)

                            for (plane in updatedPlanes) {
                                if (plane.trackingState == TrackingState.TRACKING) {
                                    // Place the 3D model on the detected horizontal plane
                                    val anchor = plane.createAnchor(plane.centerPose)
                                    val modelMatrix = FloatArray(16)
                                    anchor.pose.toMatrix(modelMatrix, 0)

                                    // Get the TransformManager from the Engine
                                    val transformManager = filamentEngine.transformManager

                                    // Convert the model matrix into a mat4 transformation matrix
                                    val transform = FloatArray(16)
                                    System.arraycopy(modelMatrix, 0, transform, 0, 16)

                                    // Apply the transformation to the entity using the TransformManager
                                    // The 0 means that the renderer only draws one model at the moment.
                                    // It will be updated to support multiple models in the future.
                                    transformManager.setTransform(0, transform)

                                    break
                                }
                            }

                            // Render the scene
                            val frameTime = System.nanoTime()
                            if (renderer.beginFrame(swapChain, frameTime)) {
                                // Render the scene
                                renderer.render(view)

                                // End the frame
                                renderer.endFrame()
                            }
                        }

                    })
                }
            },
            modifier = Modifier.fillMaxSize() // Make the GLSurfaceView fill the screen
        )
    }
}
