package de.scoopgmbh.copper.monitoring.client.form.filter.enginefilter;

import java.net.URL;
import java.util.List;

import javafx.scene.Node;
import de.scoopgmbh.copper.monitoring.client.form.filter.FilterController;
import de.scoopgmbh.copper.monitoring.client.form.filter.GenericFilterController;
import de.scoopgmbh.copper.monitoring.client.form.filter.enginefilter.EnginePoolFilterModel;
import de.scoopgmbh.copper.monitoring.core.model.ProcessingEngineInfo;

public class GenericEngineFilterController<T extends EnginePoolFilterModel> extends BaseEngineFilterController<T>{

	private final T filter;
	private long refereshIntervall;
	public GenericEngineFilterController(T filter,long refereshIntervall, List<ProcessingEngineInfo> availableEngines) {
		super(availableEngines,filter);
		this.filter = filter;
		this.refereshIntervall = refereshIntervall;
	}
	
	public GenericEngineFilterController(T filter, List<ProcessingEngineInfo> availableEngines) {
		this(filter,FilterController.DEFAULT_REFRESH_INTERVALL,availableEngines);
	}
	
	public GenericEngineFilterController(long refereshIntervall, List<ProcessingEngineInfo> availableEngines) {
		this(null,refereshIntervall,availableEngines);
	}

	@Override
	public URL getFxmlRessource() {
		return GenericFilterController.EMPTY_DUMMY_URL;
	}

	@Override
	public boolean supportsFiltering() {
		return filter!=null;
	}
	
	@Override
	public long getDefaultRefreshIntervall() {
		return refereshIntervall;
	}

	@Override
	public Node createAdditionalFilter() {
		return null;
	}

}
