package luciObjViewer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import ch.fhnw.ether.controller.IController;
import ch.fhnw.ether.render.IRenderManager;
import ch.fhnw.ether.scene.I3DObject;
import ch.fhnw.ether.scene.IScene;
import ch.fhnw.ether.scene.light.ILight;
import ch.fhnw.ether.scene.mesh.IMesh;

public class Scenario implements IScene {
	
	private final IController controller;

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
		else if (object instanceof IMesh)
			rm.addMesh((IMesh) object);
		objects.put(object.getName(), object);
	}

	@Override
	public void remove3DObject(I3DObject object) {
		if (!objects.containsKey(object.getName()))
			throw new IllegalArgumentException("object not in scene: " + object);

		IRenderManager rm = controller.getRenderManager();
		if (object instanceof ILight)
			rm.removeLight((ILight) object);
		else if (object instanceof IMesh)
			rm.removeMesh((IMesh) object);
		objects.remove(object.getName());
	}
	
	public void remove3DObject(int ScID, int geomID) {
		Collection<I3DObject> objects = get3DObjects();
		objects.forEach(object -> {
			if(object.getName().equals(ScID+"/"+geomID)){
				remove3DObject(object);
			}
		});
	}


	@Override
	public Collection<I3DObject> get3DObjects() {
		return objects.values();
	}
	
	public void update3DObject(I3DObject object) {
		boolean existing = false;
		Collection<I3DObject> objects = get3DObjects();
		for(I3DObject object_cmp: objects) {
			if(object_cmp.getName().equals(object.getName())) {
				remove3DObject(object_cmp);
				add3DObject(object);
				existing = true;
				break;
			}
		}
		if(!existing) {
			add3DObject(object);
		}
	}

}
