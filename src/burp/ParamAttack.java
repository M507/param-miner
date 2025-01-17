package burp;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.collections4.queue.CircularFifoQueue;

import java.util.*;

import static java.lang.Math.min;

class ParamAttack {

    CircularFifoQueue<String> recentParams;
    HashSet<String> alreadyReported;
    ArrayList<String> params;
    ArrayList<String> valueParams;
    int seed = -1;
    boolean started;

    private WordProvider bonusParams;
    private HashMap<String, String> requestParams;
    private ParamHolder paramBuckets;
    private int bucketSize = 1;
    private IHttpRequestResponse baseRequestResponse;
    private PayloadInjector injector;
    private String attackID;
    private Attack base;
    private String targetURL;
    private Attack altBase;
    private boolean tryMethodFlip;
    private final ParamInsertionPoint insertionPoint;
    final byte type;
    private ConfigurableSettings config;

    int getStop() {
        return stop;
    }

    void incrStop() {
        stop += config.getInt("rotation increment");
    }

    private int stop;

    WordProvider getBonusParams() {
        return bonusParams;
    }

    HashMap<String, String> getRequestParams() {
        return requestParams;
    }

    String getTargetURL() {
        return targetURL;
    }

    ParamInsertionPoint getInsertionPoint() {
        return insertionPoint;
    }

    byte[] getInvertedBase() {
        return invertedBase;
    }

    private byte[] invertedBase;

    Attack getAltBase() {
        return altBase;
    }

    boolean shouldTryMethodFlip() {
        return tryMethodFlip;
    }

    Attack getBase() {
        return base;
    }

    String getAttackID() {
        return attackID;
    }

    PayloadInjector getInjector() {
        return injector;
    }

    ParamHolder getParamBuckets() {
        return paramBuckets;
    }

    int getBucketSize() {
        return bucketSize;
    }

    IHttpRequestResponse getBaseRequestResponse() {
        return baseRequestResponse;
    }


    ParamAttack(IHttpRequestResponse baseRequestResponse, byte type, ParamGrabber paramGrabber, int stop, ConfigurableSettings config) {
        started = false;
        this.type = type;
        this.stop = stop;
        this.config = config;
        this.baseRequestResponse = baseRequestResponse;
        // Print hostname
        //targetURL = baseRequestResponse.getHttpService().getHost();
        // Print Full URL
        targetURL = Utilities.analyzeRequest(baseRequestResponse).getUrl().toString();
        params = calculatePayloads(baseRequestResponse, paramGrabber, type);
        valueParams = new ArrayList<>();
        for(int i = 0; i< params.size(); i++) {
            String candidate = params.get(i);
            if(candidate.contains("~")) {
                params.set(i, candidate.split("~", 2)[0]);
                if (!valueParams.contains(candidate)) {
                    valueParams.add(candidate);
                }
            }
        }

        // prevents attack cross-talk with stored input detection
        attackID = Utilities.mangle(Arrays.hashCode(baseRequestResponse.getRequest())+"|"+System.currentTimeMillis()).substring(0,2);

        requestParams = new HashMap<>();
        for (String entry: Keysmith.getAllKeys(baseRequestResponse.getRequest(), new HashMap<>())) {
            String[] parsed = Keysmith.parseKey(entry);
            requestParams.putIfAbsent(parsed[1], parsed[0]);
        }

        final String payload = ""; // formerly "<a`'\\\"${{\\\\"


        insertionPoint = getInsertionPoint(baseRequestResponse, type, payload, attackID);

        injector = new PayloadInjector(baseRequestResponse, insertionPoint);

        updateBaseline();

        //String ref = Utilities.getHeader(baseRequestResponse.getRequest(), "Referer");
        //HashMap<String, Attack> baselines = new HashMap<>();
        //baselines.put(ref, new Attack(baseRequestResponse));
        invertedBase = null;
        altBase = null;
        tryMethodFlip = false;

        int longest = config.getInt("max param length");

        // fixme this may exceed the max bucket size
        calculateBucketSize(type, longest);

        if (!Utilities.globalSettings.getBoolean("carpet bomb")) {
            StringBuilder basePayload = new StringBuilder();
            for (int i = 1; i < min(8, bucketSize); i++) {
                basePayload.append("|");
                basePayload.append(Utilities.randomString(longest));
                if (i % 4 == 0) {
                    base.addAttack(injector.probeAttack(basePayload.toString()));
                }
            }
        }

        // calculateBucketSize(type, longest); was here

        recentParams = new CircularFifoQueue<>(bucketSize *3);
        Utilities.log("Selected bucket size: "+ bucketSize + " for "+ targetURL);

        if(baseRequestResponse.getRequest()[0] != 'G') {
            invertedBase = Utilities.helpers.toggleRequestMethod(baseRequestResponse.getRequest());
            altBase = new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase));
            if(Utilities.helpers.analyzeResponse(altBase.getFirstRequest().getResponse()).getStatusCode() != 404 && Utilities.globalSettings.getBoolean("try method flip")) {
                altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                tryMethodFlip = true;
            }
        }

        // put the params into buckets
        paramBuckets = new ParamHolder(type, bucketSize);
        paramBuckets.addParams(valueParams, false);
        paramBuckets.addParams(params, false);

        if (!config.getBoolean("dynamic keyload")) {
            params = null;
            valueParams = null;
        }

        alreadyReported = getBlacklist(type);
        //Utilities.log("Trying " + (valueParams.size()+ params.size()) + " params in ~"+ paramBuckets.size() + " requests. Going from "+start + " to "+stop);
    }

    private void calculateBucketSize(byte type, int longest) {
        if (config.getInt("force bucketsize") != -1) {
            bucketSize = config.getInt("force bucketsize");
            return;
        }

        switch(type) {
            case IParameter.PARAM_BODY:
                bucketSize = 128;
                break;
            case Utilities.PARAM_HEADER:
                bucketSize = 8;
            case IParameter.PARAM_URL:
                bucketSize = 16;
                break;
            default:
                bucketSize = 32;
        }

        while (true) {
            Utilities.log("Trying bucket size: "+ bucketSize);
            long start = System.currentTimeMillis();
            StringBuilder trialPayload = new StringBuilder();
            trialPayload.append(Utilities.randomString(longest));
            for (int i = 0; i < bucketSize; i++) {
                trialPayload.append("|");
                trialPayload.append(Utilities.randomString(longest));
            }

            Attack trial = injector.probeAttack(trialPayload.toString());
            if (!Utilities.similar(base, trial)) {
                trial.addAttack(injector.probeAttack(trialPayload.toString()));
                trial.addAttack(injector.probeAttack(trialPayload.toString()));
                if (!Utilities.similar(base, trial)) {
                    bucketSize = bucketSize / 2;
                    break;
                }
            }

            long end = System.currentTimeMillis();
            if (end - start > 5000) {
                bucketSize = bucketSize / 2;
                Utilities.out("Setting bucketSize to "+bucketSize+" due to slow response");
                break;
            }

            if (bucketSize >= Utilities.globalSettings.getInt("max bucketsize")) {
                break;
            }

            bucketSize = bucketSize * 2;
        }
    }

    private HashSet<String> getBlacklist(byte type) {
        HashSet<String> blacklist = new HashSet<>();
        switch(type) {
            case IParameter.PARAM_COOKIE:
                blacklist.add("__cfduid");
                blacklist.add("PHPSESSID");
                blacklist.add("csrftoken");
                blacklist.addAll(Keysmith.getParamKeys(baseRequestResponse.getRequest(), new HashSet<>(IParameter.PARAM_COOKIE)));
                break;
            case IParameter.PARAM_URL:
                blacklist.add("lang");
                blacklist.addAll(Keysmith.getParamKeys(baseRequestResponse.getRequest(), new HashSet<>(IParameter.PARAM_URL, IParameter.PARAM_BODY)));
                break;
            case IParameter.PARAM_BODY:
                blacklist.addAll(Keysmith.getParamKeys(baseRequestResponse.getRequest(), new HashSet<>(IParameter.PARAM_URL, IParameter.PARAM_BODY)));
                break;
            case Utilities.PARAM_HEADER:
                if (Utilities.globalSettings.getBoolean("skip boring words")) {
                    blacklist.addAll(Utilities.boringHeaders);
                }
                break;
            default:
                Utilities.out("Unrecognised type: "+type);
                break;
        }

        if (Utilities.globalSettings.getBoolean("only report unique params")) {
            blacklist.addAll(Utilities.reportedParams);
        }

        return blacklist;
    }

    Attack updateBaseline() {
        this.base = this.injector.probeAttack(Utilities.randomString(6));
        for(int i=0; i<4; i++) {
            base.addAttack(this.injector.probeAttack(Utilities.randomString((i+1)*(i+1))));
        }
        if (bucketSize > 1) {
            base.addAttack(this.injector.probeAttack(Utilities.randomString(6) + "|" + Utilities.randomString(12)));
        }
        return base;
    }


    private static ParamInsertionPoint getInsertionPoint(IHttpRequestResponse baseRequestResponse, byte type, String payload, String attackID) {
        switch(type) {
            case IParameter.PARAM_JSON:
                return new JsonParamNameInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type, attackID);
            case Utilities.PARAM_HEADER:
                return new HeaderNameInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type, attackID);
            default:
                return new ParamNameInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type, attackID);
        }
    }

    ArrayList<String> calculatePayloads(IHttpRequestResponse baseRequestResponse, ParamGrabber paramGrabber, byte type) {
        ArrayList<String> params = new ArrayList<>();

        // collect keys in request, for key skipping, matching and re-mapping
        HashMap<String, String> requestParams = new HashMap<>();
        for (String entry: Keysmith.getAllKeys(baseRequestResponse.getRequest(), new HashMap<>())) { // todo give precedence to shallower keys
            String[] parsed = Keysmith.parseKey(entry);
            Utilities.log("Request param: " +parsed[1]);
            requestParams.putIfAbsent(parsed[1], parsed[0]);
        }

        // add JSON from response
        params.addAll(Keysmith.getAllKeys(baseRequestResponse.getResponse(), requestParams));

        // add JSON from method-flip response
        if(baseRequestResponse.getRequest()[0] != 'G') {
            IHttpRequestResponse getreq = Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(),
                    Utilities.helpers.toggleRequestMethod(baseRequestResponse.getRequest()));
            params.addAll(Keysmith.getAllKeys(getreq.getResponse(), requestParams));
        }


        // add JSON from elsewhere
        HashMap<Integer, Set<String>> responses = new HashMap<>();

        Iterator<IHttpRequestResponse> savedJson = paramGrabber.getSavedJson().iterator();
        while (savedJson.hasNext()) {
            IHttpRequestResponse resp = null; // todo record resp
            try {
                resp = savedJson.next();
            }
            catch (NoSuchElementException e) {
                break;
            }

            JsonParser parser = new JsonParser();
            JsonElement json = parser.parse(Utilities.getBody(resp.getResponse()));
            HashSet<String> keys = new HashSet<>(Keysmith.getJsonKeys(json, requestParams));
            int matches = 0;
            for (String requestKey: keys) {
                if (requestParams.containsKey(requestKey) || requestParams.containsKey(Keysmith.parseKey(requestKey)[1])) {
                    matches++;
                }
            }

            // if there are no matches, don't bother with prefixes
            // todo use root (or non-leaf) objects only
            if(matches < 1) {
                //Utilities.out("No matches, discarding prefix");
                HashSet<String> filteredKeys = new HashSet<>();
                for(String key: keys) {
                    String lastKey = Keysmith.parseKey(key)[1];
                    if (Utilities.parseArrayIndex(lastKey) < 3) {
                        filteredKeys.add(Keysmith.parseKey(key)[1]);
                    }
                }
                keys = filteredKeys;
            }

            Integer matchKey = matches;
            if(responses.containsKey(matchKey)) {
                responses.get(matchKey).addAll(keys);
            }
            else {
                responses.put(matchKey, keys);
            }
        }


        final TreeSet<Integer> sorted = new TreeSet<>(Collections.reverseOrder());
        sorted.addAll(responses.keySet());
        for(Integer key: sorted) {
            Utilities.log("Loading keys with "+key+" matches");
            ArrayList<String> sortedByLength = new ArrayList<>(responses.get(key));
            sortedByLength.sort(new LengthCompare());
            params.addAll(sortedByLength);
        }

        if (params.size() > 0) {
            Utilities.log("Loaded " + new HashSet<>(params).size() + " params from response");
        }

        params.addAll(Keysmith.getWords(Utilities.helpers.bytesToString(baseRequestResponse.getResponse())));

        if (config.getBoolean("request")) {
            params.addAll(Keysmith.getWords(Utilities.helpers.bytesToString(baseRequestResponse.getRequest())));
        }

        // todo move this stuff elsewhere - no need to load it into memory in advance
        params.addAll(paramGrabber.getSavedGET());

        params.addAll(paramGrabber.getSavedWords());

        // de-dupe without losing the ordering
        params = new ArrayList<>(new LinkedHashSet<>(params));

        bonusParams = new WordProvider();

        if (config.getBoolean("use custom wordlist")) {
            bonusParams.addSource(config.getString("custom wordlist path"));
        }

        if (config.getBoolean("use assetnote params")) {
            bonusParams.addSource("/assetnote-params");
        }


        if (type == Utilities.PARAM_HEADER && config.getBoolean("use basic wordlist")) {
            bonusParams.addSource("/headers");
        }

        if (config.getBoolean("response")) {
            if (type == Utilities.PARAM_HEADER) {
                params.replaceAll(x -> x.toLowerCase().replaceAll("[^a-z0-9_-]", ""));
                params.replaceAll(x -> x.replaceFirst("^[_-]+", ""));
                params.remove("");
            }

            params.replaceAll(x -> x.substring(0, min(x.length(), config.getInt("max param length"))));

            bonusParams.addSource(String.join("\n", params));
        }

        if (type != Utilities.PARAM_HEADER && config.getBoolean("use basic wordlist")) {
            bonusParams.addSource("/params");
        }

        if (config.getBoolean("use bonus wordlist")) {
            bonusParams.addSource("/functions");
            if (type != Utilities.PARAM_HEADER) {
                bonusParams.addSource("/headers");
            }
            else {
                bonusParams.addSource("/params");
            }
            bonusParams.addSource("/words");
        }

        // only use keys if the request isn't JSON
        // todo accept two levels of keys if it's using []
        //if (type != IParameter.PARAM_JSON) {
        //    for(int i=0;i<params.size();i++) {
        //        params.set(i, Keysmith.parseKey(params.get(i))[1]);
        //    }
        //}



        return new ArrayList<>();
    }
}
