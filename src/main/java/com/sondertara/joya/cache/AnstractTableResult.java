package com.sondertara.joya.cache;

import com.sondertara.joya.core.model.TableDTO;

import java.util.List;

public abstract class AnstractTableResult {

    public abstract List<TableDTO> load();
}
