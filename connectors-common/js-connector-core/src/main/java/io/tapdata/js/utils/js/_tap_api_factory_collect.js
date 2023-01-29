function isParam(param) {
    return typeof (param) != 'undefined';
}

class TapApi {
    invoker;
    config;

    constructor(invoker) {
        this.invoker = invoker;
        this.config = _tapConfig_;
    }

    invoke(uriOrNameStr, paramsMap, methodStr, hasInvoker) {
        let result = null;
        if (!isParam(uriOrNameStr)) {
            log.error("No API name or URL was specified, unable to execute http request. ");
            return null;
        }
        if (isParam(paramsMap) && isParam(methodStr) && isParam(hasInvoker)) {
            result = this.invoker.invoke(uriOrNameStr, tapUtil.mergeData(this.config, paramsMap), methodStr, hasInvoker);
        } else if (isParam(paramsMap) && isParam(methodStr)) {
            result = this.invoker.invoke(uriOrNameStr, tapUtil.mergeData(this.config, paramsMap), methodStr);
        } else if (isParam(paramsMap)) {
            result = this.invoker.invoke(uriOrNameStr, tapUtil.mergeData(this.config, paramsMap));
        } else {
            result = this.invoker.invoke(uriOrNameStr, this.config);
        }
        return {
            "result": tapUtil.toMap(result.result).data,
            "httpCode": result.httpCode,
            "headers": result.headers,
            "error": result.error
        };
    }

    addConfig(connection, node){
        this.config = tapUtil.mixedData(connection,node);
    }

    invokeAndCache(uriOrNameStr, paramsMap, methodStr, hasInvoker) {
        let result = this.invoke(uriOrNameStr, paramsMap, methodStr, hasInvoker);
        if (isParam(uriOrNameStr) && null != result) {
            //return tapCache.save(uriOrNameStr, result);
        }
        return result;
    }

    getFromCache(name) {
        if (!isParam(name)) {
            log.info("Invalid name will not get data from cache. ");
        }
        let cacheResult = null;//tapCache.get(name);
        return core.toMap(cacheResult);
    }

    saveToCache(key, data, saveSec) {
        if (!isParam(name)) {
            log.info("Invalid name will not save data to cache. ");
        }
        if (!isParam(name)) {
            log.info("Invalid data will not save data to cache. ");
        }
        if (isParam(saveSec) && !isNaN(saveSec)) {
            //return tapCache.save(uriOrNameStr, result, saveSec);
        } else {
            //return tapCache.save(uriOrNameStr, result);
        }
    }

    releaseFromCache(key) {
        if (!isParam(key)) {
            log.info("Key name is empty, cannot release data. ");
        }
        //return tapCache.release(key);
    }
}

function loadAPI(apiContent, type, params) {
    if (isParam(apiContent) && isParam(type) && isParam(params)) {
        return new TapApi(tapAPI.loadAPI(apiContent, type, params));
    } else if (isParam(params)) {
        return new TapApi(tapAPI.loadAPI(params));
    } else {
        return new TapApi(tapAPI.loadAPI());
    }
}


function nowDate() {
    return tapUtil.nowToDateStr();
}

function nowDateTime() {
    return tapUtil.nowToDateTimeStr();
}

function formatDate(time) {
    return tapUtil.longToDateStr(time);
}

function formatDateTime(time) {
    return tapUtil.longToDateStr(time);
}

function elementSearch(array, index) {
    return tapUtil.elementSearch(array, index);
}

function firstElement(array) {
    return tapUtil.elementSearch(array, 0);
}

function isAlive() {
    return nodeIsAlive.get();
}

function convertList(list,convertMatch){
    return tapUtil.convertList(list, convertMatch);
}