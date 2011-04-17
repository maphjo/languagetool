package de.danielnaber.languagetool.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.danielnaber.languagetool.JLanguageTool;
import de.danielnaber.languagetool.Language;
import de.danielnaber.languagetool.rules.RuleMatch;
import de.danielnaber.languagetool.rules.bitext.BitextRule;
import de.danielnaber.languagetool.tools.StringTools;
import de.danielnaber.languagetool.tools.Tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;

class LanguageToolHttpHandler implements HttpHandler {

  private static final String CONTENT_TYPE_VALUE = "text/xml; charset=UTF-8";
  /**
   * JLanguageTool instances for each language (created and configured on first use).
   * Instances are organized by language and mother language.
   * This is like a tree: first level contains the Languages, next level contains JLanguageTool instances for each mother tongue.
   */
  private static final Map<Language, Map<Language, JLanguageTool>> INSTANCES = new HashMap<Language, Map<Language, JLanguageTool>>();
  private static final Set<String> ALLOWED_IPS = new HashSet<String>();
  static {
    // accept only requests from localhost.
    // TODO: find a cleaner solution
    ALLOWED_IPS.add("/0:0:0:0:0:0:0:1"); // Suse Linux IPv6 stuff
    ALLOWED_IPS.add("/0:0:0:0:0:0:0:1%0"); // some(?) Mac OS X
    ALLOWED_IPS.add("/127.0.0.1");
  }
  private static final int CONTEXT_SIZE = 40; // characters

  public void handle(HttpExchange t) throws IOException {
    synchronized (INSTANCES) {
      final URI requestedUri = t.getRequestURI();
      final Map<String, String> parameters = getRequestQuery(t, requestedUri);
      final long timeStart = System.currentTimeMillis();
      String text = null;
      try {
        if (StringTools.isEmpty(requestedUri.getRawPath())) {
          t.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, 0);
          throw new RuntimeException("Error: Access to " + requestedUri.getPath() + " denied");
        }
        if (ALLOWED_IPS.contains(t.getRemoteAddress().getAddress().toString())) {
          if (requestedUri.getRawPath().endsWith("/Languages")) {
            // request type: list known languages
            printListOfLanguages(t);
          } else {
            // request type: text checking
            text = parameters.get("text");
            if (text == null) {
              throw new IllegalArgumentException("Missing 'text' parameter");
            }
            text = checkText(text, t, parameters, timeStart);
          }
        } else {
          t.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, 0);
          throw new RuntimeException("Error: Access from " + t.getRemoteAddress().toString() + " denied");
        }
      } catch (Exception e) {
        if (HTTPServer.verbose) {
          print("Exception was caused by this text: " + text);
        }
        e.printStackTrace();
        final String response = "Error: " + StringTools.escapeXML(e.toString());
        t.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, response.getBytes().length);
        t.getResponseBody().write(response.getBytes());
        t.close();
      }
    }
  }

  private Map<String, String> getRequestQuery(HttpExchange t, URI requestedUri) throws IOException {
    Map<String, String> parameters;
    if ("post".equalsIgnoreCase(t.getRequestMethod())) { // POST
      final InputStreamReader isr = new InputStreamReader(t.getRequestBody(), "utf-8");
      try {
        final BufferedReader br = new BufferedReader(isr);
        try {
          final String query = br.readLine();
          parameters = parseQuery(query);
        } finally {
          br.close();
        }
      } finally {
        isr.close();
      }
    } else {   // GET
      final String query = requestedUri.getRawQuery();
      parameters = parseQuery(query);
    }
    return parameters;
  }

  private void printListOfLanguages(HttpExchange t) throws IOException {
    t.getResponseHeaders().set("Content-Type", CONTENT_TYPE_VALUE);
    final String response = getSupportedLanguagesAsXML();
    t.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes().length);
    t.getResponseBody().write(response.getBytes());
    t.close();
  }

  private String checkText(String text, HttpExchange t, Map<String, String> parameters, long timeStart) throws Exception {
    final String langParam = parameters.get("language");
    if (langParam == null) {
      throw new IllegalArgumentException("Missing 'language' parameter");
    }
    final Language lang = Language.getLanguageForShortName(langParam);
    if (lang == null) {
      throw new IllegalArgumentException("Unknown language '" + langParam + "'");
    }
    final String motherTongueParam = parameters.get("motherTongue");
    Language motherTongue = null;
    if (null != motherTongueParam) {
      motherTongue = Language.getLanguageForShortName(motherTongueParam);
    }

    // TODO: how to take options from the client?
    // TODO: customize LT here after reading client options

    final List<RuleMatch> matches;
    final String sourceText = parameters.get("srctext");
    if (sourceText == null) {
      final JLanguageTool lt = getLanguageToolInstance(lang, motherTongue);
      print("Checking " + text.length() + " characters of text, language " + langParam);
      matches = lt.check(text);
    } else {
      if (motherTongueParam == null) {
        throw new IllegalArgumentException("Missing 'motherTongue' for bilingual checks");
      }
      print("Checking bilingual text, with source length " + sourceText.length() +
          " and target length " + text.length() + " (characters), source language " +
          motherTongue + "and target language " + langParam);
      final JLanguageTool sourceLt = getLanguageToolInstance(motherTongue, null);
      final JLanguageTool targetLt = getLanguageToolInstance(lang, null);
      final List<BitextRule> bRules = Tools.getBitextRules(motherTongue, lang);
      matches = Tools.checkBitext(sourceText, text, sourceLt, targetLt, bRules);
    }
    t.getResponseHeaders().set("Content-Type", CONTENT_TYPE_VALUE);

    final String response = StringTools.ruleMatchesToXML(matches, text,
            CONTEXT_SIZE, StringTools.XmlPrintMode.NORMAL_XML);

    print("Check done in " + (System.currentTimeMillis() - timeStart) + "ms");

    t.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes().length);
    t.getResponseBody().write(response.getBytes());
    t.close();
    return text;
  }

  private Map<String, String> parseQuery(String query) throws UnsupportedEncodingException {
    final Map<String, String> parameters = new HashMap<String, String>();
    if (query != null) {
      final String[] pairs = query.split("[&]");
      for (String pair : pairs) {
        final String param = pair.substring(0, pair.indexOf("="));
        String key = null;
        String value;
        if (param != null) {
          key = URLDecoder.decode(param, System.getProperty("file.encoding"));
        }
        if (pair.substring(pair.indexOf("=") + 1) == null || pair.substring(pair.indexOf("=") + 1).equals("")) {
          value = "";
        } else {
          value = URLDecoder.decode(pair.substring(pair.indexOf("=") + 1), "UTF-8");
        }
        value = value.replaceAll("\\+", " ");
        parameters.put(key, value);
      }
    }
    return parameters;
  }

  private void print(String s) {
    System.out.println(getDate() + " " + s);
  }

  private String getDate() {
    final SimpleDateFormat sdf = new SimpleDateFormat();
    return sdf.format(new Date());
  }

  /**
   * Find or create a JLanguageTool instance for a specific language and mother tongue.
   * The instance will be reused. If any customization is required (like disabled rules), 
   * it will be done after acquiring this instance.
   * 
   * @param lang the language to be used.
   * @param motherTongue the user's mother tongue or <code>null</code>
   * @return a JLanguageTool instance for a specific language and mother tongue.
   * @throws Exception when JLanguageTool creation failed
   */
  private JLanguageTool getLanguageToolInstance(Language lang, Language motherTongue) throws Exception {
    Map<Language, JLanguageTool> languageTools = INSTANCES.get(lang);
    if (null == languageTools) {
      // first call using this language
      languageTools = new HashMap<Language, JLanguageTool>();
      INSTANCES.put(lang, languageTools);
    }
    final JLanguageTool languageTool = languageTools.get(motherTongue);
    if (null == languageTool) {
      print("Creating JLanguageTool instance for language " + lang + ((null != motherTongue) ? (" and mother tongue " + motherTongue) : ""));
      final JLanguageTool newLanguageTool = new JLanguageTool(lang, motherTongue);
      newLanguageTool.activateDefaultPatternRules();
      newLanguageTool.activateDefaultFalseFriendRules();
      languageTools.put(motherTongue, newLanguageTool);
      return newLanguageTool;
    }
    return languageTool;
  }

  /**
   * Construct an xml string containing all supported languages. <br/>The xml format is:<br/>
   * &lt;languages&gt;<br/>
   *	&nbsp;&nbsp;&lt;language name="Catalan" abbr="ca" /&gt;<br/> 
   *    &nbsp;&nbsp;&lt;language name="Dutch" abbr="nl" /&gt;<br/>
   *    &nbsp;&nbsp;...<br/>
   *  &lt;languages&gt;<br/>
   *  The languages are alphabetically sorted.  
   * @return an xml string containing all supported languages.
   */
  public static String getSupportedLanguagesAsXML() {
    final List<Language> languages = Arrays.asList(Language.REAL_LANGUAGES);
    Collections.sort(languages, new Comparator<Language>() {
      public int compare(Language o1, Language o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });
    final StringBuilder xmlBuffer = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<languages>\n");
    for (Language lang : languages) {
      xmlBuffer.append(String.format("\t<language name=\"%s\" abbr=\"%s\" /> \n", lang.getName(), lang.getShortName()));
    }
    xmlBuffer.append("</languages>\n");
    return xmlBuffer.toString();
  }
}
