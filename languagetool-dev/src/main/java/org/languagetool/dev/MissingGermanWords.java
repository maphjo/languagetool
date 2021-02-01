package org.languagetool.dev;

import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.GermanyGerman;
import org.languagetool.rules.de.GermanSpellerRule;
import org.languagetool.rules.en.MorfologikAmericanSpellerRule;
import org.languagetool.tagging.de.GermanTagger;
import org.languagetool.tools.StringTools;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class MissingGermanWords {

  private final String filename;
  private final boolean outputCombinedListing;
  private final GermanSpellerRule germanSpeller;
  private final GermanTagger germanTagger;
  private final MorfologikAmericanSpellerRule englishSpeller;

  public MissingGermanWords(String filename) throws IOException {
    this.filename = filename;
    this.outputCombinedListing = true;
    germanSpeller = new GermanSpellerRule(JLanguageTool.getMessageBundle(), new GermanyGerman());
    germanTagger = new GermanTagger();
    englishSpeller = new MorfologikAmericanSpellerRule(JLanguageTool.getMessageBundle(), new AmericanEnglish());
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.out.println("Usage: " + MissingGermanWords.class.getSimpleName() + " <filename>");
      System.exit(1);
    }
    String filename = args[0];
    new MissingGermanWords(filename).run();
  }

  private void run() throws IOException {
    if (outputCombinedListing) {
      listMissingWords(filename);
    } else {
      listMissingWordsSpeller(filename);
      listMissingWordsTagger(filename);
    }
  }

  private void listMissingWordsSpeller(String filename) throws java.io.IOException {
    System.out.println("# missing words speller");
    BufferedReader reader = getReaderForFilename(filename);
    String word;
    while ((word = reader.readLine()) != null) {
      if (!isKnownByGermanSpeller(word) && !isKnownByEnglishSpeller(word)) {
        System.out.println(word);
      }
    }
    reader.close();
  }

  private void listMissingWordsTagger(String filename) throws java.io.IOException {
    System.out.println("# missing words tagger");
    BufferedReader reader = getReaderForFilename(filename);
    String word;
    while ((word = reader.readLine()) != null) {
      if (!isKnownByGermanTagger(word) && !isKnownByEnglishSpeller(word)) {
        System.out.println(word);
      }
    }
    reader.close();
  }

  private void listMissingWords(String filename) throws java.io.IOException {
    BufferedReader reader = getReaderForFilename(filename);
    String word;
    while ((word = reader.readLine()) != null) {
      boolean knownBySpeller = isKnownByGermanSpeller(word);
      boolean knownByTagger = isKnownByGermanTagger(word);
      if ((!knownBySpeller || !knownByTagger) && !isKnownByEnglishSpeller(word)) {
        System.out.print(word);
        System.out.print(" ");
        if (!knownBySpeller && !knownByTagger) {
          System.out.println("speller+tagger");
        } else if (!knownBySpeller) {
          System.out.println("speller");
        } else {
          System.out.println("tagger");
        }
      }
    }
    reader.close();
  }

  private boolean isKnownByGermanSpeller(String word) {
    return !germanSpeller.isMisspelled(StringTools.uppercaseFirstChar(word)) ||
      !germanSpeller.isMisspelled(StringTools.lowercaseFirstChar(word));
  }

  private boolean isKnownByGermanTagger(String word) throws IOException {
    return germanTagger.lookup(StringTools.uppercaseFirstChar(word)) != null ||
      germanTagger.lookup(StringTools.lowercaseFirstChar(word)) != null;
  }

  private boolean isKnownByEnglishSpeller(String word) throws IOException {
    return !englishSpeller.isMisspelled(StringTools.uppercaseFirstChar(word)) ||
      !englishSpeller.isMisspelled(StringTools.lowercaseFirstChar(word));
  }

  private BufferedReader getReaderForFilename(String filename) throws FileNotFoundException {
    FileInputStream fis = new FileInputStream(filename);
    InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
    return new BufferedReader(isr);
  }
}
