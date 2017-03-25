package luciObjViewer;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.fhnw.ether.controller.IController;
import ch.fhnw.ether.render.IRenderManager;
import ch.fhnw.ether.scene.I3DObject;
import ch.fhnw.ether.scene.IScene;
import ch.fhnw.ether.scene.light.ILight;
import ch.fhnw.ether.scene.mesh.IMesh;
import ch.fhnw.util.math.geometry.BoundingBox;

public class Scenario implements IScene {
	
	private final IController controller;
	private BoundingBox bounds;
	private final Map<String, I3DObject> objects = new HashMap<>();

	public Scenario(IController controller) {
		this.controller = controller;
	}
	
	@Override
	public void add3DObject(I3DObject object) {
		if(object == null)
			throw new NullPointerException("object == null");
		if (objects.containsKey(object.getName()))
			throw new IllegalArgumentException("object already in scene: " + object);

		IRenderManager rm = controller.getRenderManager();
		if (object instanceof ILight)
			rm.addLight((ILight) object);
		else if (object instanceof IMesh){
			rm.addMesh((IMesh) object);
			getBounds();
			bounds.add(object.getBounds());
		}
		objects.put(object.getName(), object);
	}
	
	public BoundingBox getBounds(){
		if (bounds == null){
			bounds = new BoundingBox();
			objects.forEach((name, object) -> bounds.add(object.getBounds()));
		}
		return bounds;
	}

	public void remove3DObject(I3DObject object, boolean recalculateBoundingBox){
		if (!objects.containsKey(object.getName()))
			throw new IllegalArgumentException("object not in scene: " + object);

		IRenderManager rm = controller.getRenderManager();
		if (object instanceof ILight)
			rm.removeLight((ILight) object);
		else if (object instanceof IMesh){
			rm.removeMesh((IMesh) object);
			if (recalculateBoundingBox) 
				bounds = null;
		}
		objects.remove(object.getName());
	}
	
	@Override
	public void remove3DObject(I3DObject object) {
		remove3DObject(object, true);
	}
	
	public void remove3DObjects(int ScID, List<Integer> geomIDs) {
		geomIDs.forEach(id -> {
			I3DObject object = objects.remove(ScID+"/"+id);
			if (object != null){
				IRenderManager rm = controller.getRenderManager();
				if (object instanceof ILight)
					rm.removeLight((ILight) object);
				else if (object instanceof IMesh){
					rm.removeMesh((IMesh) object);
					bounds = null;
				}
			}
		});
	}


	@Override
	public Collection<I3DObject> get3DObjects() {
		return objects.values();
	}
	
	public void update3DObject(I3DObject object, boolean recalculateBoundingBox) {
		I3DObject old = objects.get(object.getName());
		if (old != null)
			remove3DObject(old, recalculateBoundingBox);
		add3DObject(object);
	}

}
