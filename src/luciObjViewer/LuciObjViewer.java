package luciObjViewer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONObject;

import com.esotericsoftware.minlog.Log;

import ch.fhnw.ether.controller.DefaultController;
import ch.fhnw.ether.controller.IController;
import ch.fhnw.ether.formats.obj.ObjReader;
import ch.fhnw.ether.image.IGPUImage;
import ch.fhnw.ether.platform.Platform;
import ch.fhnw.ether.render.IRenderManager;
import ch.fhnw.ether.scene.camera.ICamera;
import ch.fhnw.ether.scene.light.DirectionalLight;
import ch.fhnw.ether.scene.mesh.DefaultMesh;
import ch.fhnw.ether.scene.mesh.IMesh;
import ch.fhnw.ether.scene.mesh.MeshUtilities;
import ch.fhnw.ether.scene.mesh.IMesh.Flag;
import ch.fhnw.ether.scene.mesh.IMesh.Primitive;
import ch.fhnw.ether.scene.mesh.IMesh.Queue;
import ch.fhnw.ether.scene.mesh.geometry.DefaultGeometry;
import ch.fhnw.ether.scene.mesh.geometry.IGeometry;
import ch.fhnw.ether.scene.mesh.material.ColorMapMaterial;
import ch.fhnw.ether.scene.mesh.material.ColorMaterial;
import ch.fhnw.ether.scene.mesh.material.IMaterial;
import ch.fhnw.ether.view.DefaultView;
import ch.fhnw.ether.view.IView;
import ch.fhnw.ether.view.IView.Config;
import ch.fhnw.ether.view.IView.ViewType;
import ch.fhnw.util.color.RGB;
import ch.fhnw.util.color.RGBA;
import ch.fhnw.util.math.Vec3;
import ch.fhnw.util.math.geometry.BoundingBox;
import luci.connect.Attachment;
import luci.connect.AttachmentAsArray;
import luci.connect.AttachmentAsFile;
import luci.connect.LcRemoteService;
import luci.connect.Message;

public class LuciObjViewer extends LcRemoteService {
	
	static {
		Log.set(Log.LEVEL_TRACE);
	}
	
	public static class RemoteViewerArgsProcessor extends DefaultArgsProcessor{
		int x = 10, y = 10, w = 512, h = 512;
		boolean interactive = true;
		public RemoteViewerArgsProcessor(String[] args){
			super(args);
			int i = -1;
			List<String> largs = Arrays.asList(args);
			if ((i = largs.indexOf("-x")) >= 0) x = Integer.valueOf(largs.get(i+1));
			if ((i = largs.indexOf("-y")) >= 0) y = Integer.valueOf(largs.get(i+1));
			if ((i = largs.indexOf("-w")) >= 0) w = Integer.valueOf(largs.get(i+1));
			if ((i = largs.indexOf("-h")) >= 0) h = Integer.valueOf(largs.get(i+1));
			if ((i = largs.indexOf("-interactive")) >= 0) interactive = true;
		}
	}
	
	public static void main(String[] args){
		RemoteViewerArgsProcessor ap = new RemoteViewerArgsProcessor(args);
		LuciObjViewer remoteViewer = new LuciObjViewer(ap);
		new Thread(remoteViewer).start();
        remoteViewer.connect(ap.getHostname(), ap.getPort());
        remoteViewer.init();
	}
	
	private Scenario scene;
	IController controller;
	RemoteViewerArgsProcessor ap;
	int ScID, cameraID, w = 800, h = 600, x = 100, y = 100;
	private ICamera cam = null;
	
	private int t_height, t_width;
	
	public LuciObjViewer(RemoteViewerArgsProcessor ap) {
		super(ap);
		this.ap = ap;
	}
	
	void init(){
		Platform.get().init();
		controller = new DefaultController();
		Platform.get().run();
	}
	
	public int howToHandleAttachments() {
		return Attachment.FILE;
	}

	 @Override
    public String getDescription() {
        return "OpenGL viewer for OBJ file format";
    }

    protected JSONObject specifyInputs() {
        return new JSONObject("{'run':'scenario.Viewer'," +
                "'XOR geometry':'attachment','XOR ScID':'number'," +
                "'OPT height':'number','OPT width':'number'," +
                "'OPT positionX':'number','OPT positionY':'number'," +
                "'OPT cameraID':'number'}");
    }

    protected JSONObject specifyOutputs() {
        return new JSONObject("{'XOR result':'json','XOR error':'string'}");
    }

    protected JSONObject exampleCall() {
        return new JSONObject("{'run':'scenario.Viewer', 'geometry':'attachment'}");
    }

	@Override
	protected ResponseHandler newResponseHandler() {
		return new CallHandler();
	}

	public class CallHandler extends RemoteServiceResponseHandler{

		@Override
		public Message implementation(Message m) {
			JSONObject H = m.getHeader();
            if(H.has("width")) {
                w = H.getInt("width");
            }
            if(H.has("height")) {
                h = H.getInt("height");
            }
            if(H.has("positionX")) {
                x = H.getInt("positionX");
            }
            if(H.has("positionY")) {
                y = H.getInt("positionY");
            }
            
            if (H.has("ScID") || H.has("geometry")){
            	initScene(!H.has("cameraID"));
            }
			
			if(H.has("geometry")) {
            	ScID = 0;
            	receiveObj(m);
            } else if(H.has("ScID")) {
                ScID = H.getInt("ScID");
                send(new Message(new JSONObject("{'run':'scenario.obj.Get', 'ScID':" + ScID + "}")));
                JSONObject scenarioSubscribe = new JSONObject().put("run", "scenario.SubscribeTo")
                        .put("ScIDs", new JSONArray().put(ScID))
                        .put("format", "obj");
//                System.out.println(scenarioSubscribe);
                send(new Message(scenarioSubscribe));
            }
            if(H.has("cameraID")) {
                cameraID = H.getInt("cameraID");
                send(new Message(new JSONObject("{'run':'scenario.camera.Get', 'cameraID':" + cameraID + "}")));
                send(new Message(new JSONObject("{'run':'scenario.camera.SubscribeTo', 'cameraID':" + cameraID + "}")));
                
            }
            return new Message(new JSONObject("{'result':{'status':'started'}}"));
		}

		@Override
		public void processResult(Message m) {
			
			JSONObject h = m.getHeader();
			String serviceName = h.getString("serviceName");
			switch(serviceName){
			case "DistanceToWalls": 
				visualizeDistanceToWalls(m); 
				break;
			case "scenario.obj.Get":
				JSONObject r = h.getJSONObject("result");
				if(r.has("deletedIDs")) {
					int ScID = r.getInt("ScID");
					JSONArray deletedIDs = r.getJSONArray("deletedIDs");
					controller.run(time -> deletedIDs.forEach(deletedID -> scene.remove3DObject(ScID, (int)deletedID)));
				}
				receiveObj(m); 
				break;
			case "scenario.camera.Get":
			case "scenario.camera.Update": 
				receiveCamera(h.getJSONObject("result")); 
				break;
			case "RemoteRegister":
				System.out.println("registered as: "+m);
			}
		}
		
	}
	
	private void initScene(boolean interactive){
		Platform.get().runOnMainThread(() -> {
			Config c;
			if (interactive)
				c = new Config(ViewType.INTERACTIVE_VIEW, 0, RGBA.GRAY);
			else c = new Config(ViewType.RENDER_VIEW, 0, RGBA.GRAY);

			controller.run((time) ->{
				new DefaultView(controller, x, y, w, h, c, "Luci Scenario Viewer");
				scene = new Scenario(controller);
				controller.setScene(scene);
				scene.add3DObject(new DirectionalLight(new Vec3(1, 1, 1), RGB.BLACK, RGB.GRAY80));
				IMesh cube = MeshUtilities.createCube(new ColorMaterial(RGBA.MAGENTA));
				cube.setName("cube");
				scene.add3DObject(cube);
			});
		});
	}
	
	private void receiveObj(Message m){
		controller.run((time) ->{
			try {
				File f = ((AttachmentAsFile) m.getAttachment(0)).getFile();
//				File f = new File("/Users/treyerl/Desktop/ZH_ESUM.obj");
				int ScID = m.getHeader().getJSONObject("result").getInt("ScID");
				ObjReader obj = new ObjReader(f);
				obj.getMeshes(null, groupName -> ScID+"/"+groupName, Queue.DEPTH, EnumSet.of(Flag.DONT_CULL_FACE))
				   .forEach(mesh -> scene.update3DObject(mesh));
				f.delete();
				runIfExists("DistanceToWalls", requestDTW(obj.getObject().getBounds()));
			} catch (IOException e){
				e.printStackTrace();
			}
			IRenderManager rm = controller.getRenderManager();
			if (cam == null){
				IView view = controller.getCurrentView();
				if (view != null){
					cam = rm.getCamera(view);
				}
			}
		});
	}
	
	private void receiveCamera(JSONObject result){
    	System.out.println("new camera");
		controller.run(time -> {
			JSONObject eye = result.getJSONObject("location");
			JSONObject at = result.getJSONObject("lookAt");
			JSONObject up = result.getJSONObject("cameraUp");
			if (cam != null){
		    	System.out.println("new camera2");
				cam.setPosition(new Vec3(eye.getDouble("x"), eye.getDouble("y"), eye.getDouble("z")));
				cam.setTarget(new Vec3(at.getDouble("x"), at.getDouble("y"), at.getDouble("z")));
				cam.setUp(new Vec3(up.getDouble("x"), up.getDouble("y"), up.getDouble("z")));
				// XXX implement FieldOfView for camera sync
				//cam.setFov(fov);
			}
		});
	}
	
	private void visualizeDistanceToWalls(Message m){
        BufferedImage img = new BufferedImage(t_width, t_height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = img.createGraphics();

        Attachment att = m.getAttachmentsSortedByPosition()[0];
        try {
            InputStream is = Attachment.getInputStream(att);
//            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[4];

            ArrayList<Float> floats = new ArrayList<Float>();
            float max_dist = Float.MIN_VALUE;
            float min_dist = Float.MAX_VALUE;

            while ((nRead = is.read(data, 0, data.length)) != -1) {
                float f =  ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                floats.add(f);

                System.out.println(f);

                if(max_dist < f) max_dist = f;
                if(min_dist > f) min_dist = f;
            }


            for(int i = 0; i < t_height; i++) {
                for(int j = 0; j < t_width; j++) {
                    int index = i*t_width + j;
                    float dist = floats.get(index);
                    System.out.println("index: " + index);
                    float scaled = (dist - min_dist)*(1/(max_dist - min_dist));
                    if (scaled <= 0.5) {
                        graphics.setColor(new Color(0, 2f*scaled, 1 - 2f*scaled, 1.0f));
                    } else {
                        graphics.setColor(new Color(2f*(scaled - 0.5f), 1 - 2f*(scaled - 0.5f), 0, 1.0f));
                    }
                    System.out.println("value: " + dist);
                    System.out.println("x: " + j);
                    System.out.println("y: " + i);

                    graphics.fillRect(j, t_height - i - 1, 1, 1);
                }
            }

            System.out.println("write texture.png");
            File outputfile = new File("texture.png");
            ImageIO.write(img, "png", outputfile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        float[] vertices = { 0, 0, 0, 5f, 0, 5f, 0, 0, 5f };
		float[] colors = { 1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1 };
		float[] texCoords = { 10, 0, 10, 10, 0, 10 };
		
		try {
			File inputfile = new File("texture.png");
			IGPUImage t = IGPUImage.read(inputfile);
			IMaterial mat = new ColorMapMaterial(RGBA.WHITE, t, true);
			IGeometry g = DefaultGeometry.createVCM(vertices, colors, texCoords);
			IMesh texture_mesh = new DefaultMesh(Primitive.TRIANGLE_STRIP, mat, g);
			//texture_mesh.setTransform(transform)
			controller.run(time -> scene.update3DObject(texture_mesh));
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("cant load image");
			System.exit(1);
		}
	}
	
	private Runnable requestDTW(BoundingBox box){
		return () -> {
            // creation of grid
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            t_width = (int) (box.getMaxX() - box.getMinX());
            t_height = (int) (box.getMaxY() - box.getMinY());
            for(float i = box.getMinY(); i <= box.getMaxY(); i+=1) {
                for(float j = box.getMinX(); j <= box.getMaxY(); j+=1) {
                    byte[] bi = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(j).array();
                    byte[] bj = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(i).array();
                    byte[] b0 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).array();

                    byteStream.write(bi,0,4);
                    byteStream.write(bj,0,4);
                    byteStream.write(b0,0,4);
                }
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(byteStream.toByteArray());
            AttachmentAsArray attachment = new AttachmentAsArray("float32array", "grid", byteBuffer);
            Message distance_to_walls_message = new Message(new JSONObject()
                    .put("run", "DistanceToWalls")
                    .put("ScID", ScID)
                    .put("mode", "points")
                    .put("points", attachment));
            send(distance_to_walls_message);
		};
	}
}

