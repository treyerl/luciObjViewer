package luciObjViewer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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
import ch.fhnw.ether.scene.mesh.IMesh;
import ch.fhnw.ether.scene.mesh.IMesh.Flag;
import ch.fhnw.ether.scene.mesh.IMesh.Queue;
import ch.fhnw.ether.scene.mesh.MeshUtilities;
import ch.fhnw.ether.scene.mesh.material.ColorMapMaterial;
import ch.fhnw.ether.scene.mesh.material.IMaterial;
import ch.fhnw.ether.view.DefaultView;
import ch.fhnw.ether.view.IView;
import ch.fhnw.ether.view.IView.Config;
import ch.fhnw.ether.view.IView.ViewType;
import ch.fhnw.util.color.RGB;
import ch.fhnw.util.color.RGBA;
import ch.fhnw.util.math.Mat4;
import ch.fhnw.util.math.Vec3;
import ch.fhnw.util.math.geometry.BoundingBox;
import luci.connect.Attachment;
import luci.connect.AttachmentAsArray;
import luci.connect.JSON;
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
	
	private int t_height, t_width, maxCells = 100, numCellsX, numCellsY, cellSize;
	private boolean dtwPending;
	private boolean receiving = false;
	
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
		return Attachment.ARRAY;
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
			cam = null;
			cameraID = 0;
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
                send(new Message(new JSONObject("{'run':'scenario.camera.SubscribeTo', 'cameraID':" + cameraID + "}")));
                send(new Message(new JSONObject("{'run':'scenario.camera.Get', 'cameraID':" + cameraID + "}")));
            }
            return new Message(new JSONObject("{'result':{'status':'started'}}"));
		}

		@Override
		public void processResult(Message m) {
			
			JSONObject h = m.getHeader();
			String serviceName = h.getString("serviceName");
			switch(serviceName){
			case "DistanceToWalls":
//				System.out.println(h);
				visualizeDistanceToWalls(m); 
				break;
			case "scenario.obj.Get":
				JSONObject r = h.getJSONObject("result");
				if(r.has("deletedIDs")) {
					int ScID = r.getInt("ScID");
					JSONArray deletedIDs = r.getJSONArray("deletedIDs");
					controller.run(time -> scene.remove3DObjects(ScID, JSON.ArrayToIntList(deletedIDs)));
				}
				receiveObj(m); 
				break;
			case "scenario.camera.Get":
			case "scenario.camera.Update": 
				receiveCamera(h.getJSONObject("result")); 
				break;
			case "RemoteRegister":
				System.out.println("registered as: "+m);
				InetSocketAddress addr = m.getSourceSocketHandler().getLocalSocketAddress();
				System.out.println(addr.getAddress().getHostAddress()+":"+addr.getPort());
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
				DirectionalLight l1 = new DirectionalLight(new Vec3(0, -0.5, 1), RGB.GRAY40, (new RGBA(0xBBBCCBFF)).toRGB());
				l1.setName("mainLight");
				scene.add3DObject(l1);
			});
		});
	}
	
	private void receiveObj(Message m){
		if (!receiving){
			try {
				final int ScID = m.getHeader().getJSONObject("result").getInt("ScID");
				final ObjReader obj = new ObjReader(Attachment.getInputStream(m.getAttachment(0)));
				controller.run((time) ->{
					obj.getMeshes(null, groupName -> ScID+"/"+groupName, Queue.DEPTH, EnumSet.of(Flag.DONT_CULL_FACE))
					   .forEach(mesh -> scene.update3DObject(mesh, false));
					runIfExists("DistanceToWalls", () -> {
						if (!dtwPending) 
							requestDTW(scene.getBounds());
					});
					IRenderManager rm = controller.getRenderManager();
					if (cam == null){
						IView view = controller.getCurrentView();
						if (view != null){
							cam = rm.getCamera(view);
							cam.setNear(5f);
						}
						send(new Message(new JSONObject("{'run':'scenario.camera.Get', 'cameraID':" + cameraID + "}")));
					}
					receiving = false;
//					System.out.println("received obj");
				});
			} catch (IOException e){
				e.printStackTrace();
			}
			receiving = true;
//			System.out.println("receive obj");
		}
		
	}
	
	private void receiveCamera(JSONObject result){
//		System.out.println("receive cam");
		controller.run(time -> {
			JSONObject eye = result.getJSONObject("location");
			JSONObject at = result.getJSONObject("lookAt");
			JSONObject up = result.getJSONObject("cameraUp");
			if (cam != null){
				cam.setPosition(new Vec3(eye.getDouble("x"), eye.getDouble("y"), eye.getDouble("z")));
				cam.setTarget(new Vec3(at.getDouble("x"), at.getDouble("y"), at.getDouble("z")));
				cam.setUp(new Vec3(up.getDouble("x"), up.getDouble("y"), up.getDouble("z")));
				cam.setFov((float) result.getDouble("fov"));
			}
		});
	}
	
	private void visualizeDistanceToWalls(Message m){
		
//		System.out.println("numCellsX: "+numCellsX);
//		System.out.println("numCellsY: "+numCellsY);
//		System.out.println("width: "+t_width);
//		System.out.println("height: "+t_height);
//		System.out.println("cellSize: "+cellSize);
        final BufferedImage img = new BufferedImage(t_width, t_height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = img.createGraphics();

        Attachment att = m.getAttachmentsSortedByPosition()[0];
//        System.out.println("attlen: "+att.length());
        try {
            InputStream is = Attachment.getInputStream(att);
//            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4];

            ArrayList<Float> floats = new ArrayList<Float>();
            float max_dist = Float.MIN_VALUE;
            float min_dist = Float.MAX_VALUE;

            while ((is.read(data, 0, data.length)) != -1) {
                float f =  ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                floats.add(f);

//                System.out.println(f);

                if(max_dist < f) max_dist = f;
                if(min_dist > f) min_dist = f;
            }
            
//            ((AttachmentAsFile) att).getFile().delete();


            for (int y = 0; y < numCellsY; y++) {
//            	System.out.println("");
                for (int x = 0; x < numCellsX; x++) {
                    int index = y * numCellsX + x;
                    float dist = floats.get(index);
//                    System.out.print(" " + index + " ");
                    float scaled = (dist - min_dist)/(max_dist - min_dist);
//                    scaled = scaled*0.6f-0.4f;
//                    graphics.setColor(new Color(Color.HSBtoRGB(scaled, 1, 1)));
                    if (scaled <= 0.5) {
                        graphics.setColor(new Color(0, 2f*scaled, 1 - 2f*scaled, 1.0f));
                    } else {
                        graphics.setColor(new Color(2f*(scaled - 0.5f), 1 - 2f*(scaled - 0.5f), 0, 1.0f));
                    }
//                    System.out.println("value: " + dist);
//                    System.out.println("x: " + j);
//                    System.out.println("y: " + i);

                    graphics.fillRect(x * cellSize, t_height - (y+1) * cellSize, cellSize, cellSize);
                }
            }

            System.out.println("write texture.png");
            File outputfile = new File("texture.png");
            ImageIO.write(img, "png", outputfile);
        } catch (IOException e) {
            e.printStackTrace();
        }
		
		try {
			File inputfile = new File("texture.png");
			IGPUImage t = IGPUImage.read(inputfile);
			IMaterial mat = new ColorMapMaterial(RGBA.WHITE, t);
			IMesh texture_mesh = MeshUtilities.createQuad(mat);
			texture_mesh.setTransform(Mat4.scale(new Vec3(t_width/2, t_height/2, 1)));
			controller.run(time -> {
				scene.update3DObject(texture_mesh, false);
				dtwPending = false;
			});
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("cant load image");
			System.exit(1);
		}
	}
	
	private void requestDTW(BoundingBox box){
		dtwPending = true;
		// creation of grid
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        t_width = (int) (box.getMaxX() - box.getMinX());
        t_height = (int) (box.getMaxY() - box.getMinY());
        cellSize = (int) Math.ceil(Math.max(t_height, t_width) / maxCells);
        numCellsX = (int) (t_width / cellSize);
        numCellsY = (int) (t_height/ cellSize);
        t_width = numCellsX * cellSize;
        t_height = numCellsY * cellSize;
         
        for(float y = -t_height/2, j = 0; j < numCellsY; y+=cellSize, j++) {
//        	System.out.println("");
            for(float x = -t_width/2, i = 0; i < numCellsX; x+=cellSize, i++) {
//            	System.out.print(x+"/"+y+"  ");
                byte[] bx = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).array();
                byte[] by = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(y).array();
                byte[] b0 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).array();

                byteStream.write(bx,0,4);
                byteStream.write(by,0,4);
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
	}
}

