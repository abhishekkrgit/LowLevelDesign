import jdk.jshell.SourceCodeAnalysis;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

class TrieNode{
    private final Map<Character, TrieNode> children;
    private long freq;
    private boolean isEndHere;
    private final Lock lock = new ReentrantLock();

    TrieNode(){
        children = new ConcurrentHashMap<>();
        freq = 0;
        isEndHere = false;
    }

    public boolean isEndHere(){
        return isEndHere;
    }
    public void setEndValue(boolean isEndHere) {
        this.isEndHere = isEndHere;
    }
    public void incrementFreq() {
        lock.lock();
        try{
            freq++;
        } finally {
            lock.unlock();
        }
    }

    public void decrementFreq() {
        lock.lock();
        try{
            if(freq <= 0){
                return;
            }
            freq--;
        } finally {
            lock.unlock();
        }
    }

    public long getFreq(){
        return freq;
    }

    public TrieNode getChildNode(char ch){
        return children.getOrDefault(ch, null);
    }

    public  void setChildNode(char ch, TrieNode node){
        children.put(ch, node);
    }

    public List<Character> getChildren(){
        List<Character>list = new ArrayList<>();
        for(Character ch : children.keySet()){
            list.add(ch);
        }
        return list;
    }


}

class Suggestions{
    private String word;
    private long count;

    Suggestions(String word, long count){
        this.word = word;
        this.count = count;
    }

    public String getWord(){
        return word;
    }
    public long getCount(){
        return count;
    }
}

class Trie {
    private TrieNode root;
    public Trie(){
        root = new TrieNode();
    }

    public void insertWord(String word){
        TrieNode node = root;
        for(char ch: word.toCharArray()) {
            if (node.getChildNode(ch) == null) {
                TrieNode  newNode = new TrieNode();
                node.setChildNode(ch, newNode);
            }
            node = node.getChildNode(ch);
        }
        node.setEndValue(true);
        node.incrementFreq();
    }

    private TrieNode searchPrefix (String word){
        TrieNode node = root;
        for(int i = 0; i < word.length(); i++){
            char c = word.charAt(i);
            if(node.getChildNode(c) == null){
                return null;
            }
            node = node.getChildNode(c);
        }
        return node;
    }

    public boolean searchWord(String word){
        TrieNode node = searchPrefix(word);
        if(node == null){
            return false;
        }
        else return node.isEndHere();
    }

    public boolean containsWord(String word){
        TrieNode node = searchPrefix(word);
        if(node == null){
            return false;
        }
        return true;
    }

    private void collectSuggestions(List<Suggestions> suggestionsList, TrieNode currNode, String word){
        if(currNode == null){
            return;
        }

        if(currNode.isEndHere()){
            suggestionsList.add(new Suggestions(word, currNode.getFreq()));
        }

        if(currNode.getChildren() == null) return;
        for(Character ch: currNode.getChildren()){
            collectSuggestions(suggestionsList, currNode.getChildNode(ch), word + ch.toString());
        }
    }

    public List<Suggestions>  getSuggestions(String word){
        List<Suggestions>suggestions = new ArrayList<>();
        TrieNode node = searchPrefix(word);
        if(node == null){
           return suggestions;
        }
        collectSuggestions(suggestions, node, word);

        return suggestions;
    }
}


interface RankingStrategy {
    List<Suggestions> getSuggestions(List<Suggestions> suggestionsList);
}

class AlphabeticalRankingStrategy implements RankingStrategy {
    @Override
    public List<Suggestions> getSuggestions(List<Suggestions> suggestionsList) {
        return suggestionsList.stream()
                .sorted(Comparator.comparing(Suggestions::getWord))
                .collect(Collectors.toList());
    }
}

class FrequencyRankingStrategy implements RankingStrategy {
    @Override
    public List<Suggestions> getSuggestions(List<Suggestions> suggestionsList) {
        return suggestionsList.stream()
                .sorted(Comparator.comparing(Suggestions::getCount).reversed())
                .collect(Collectors.toList());
    }

}

public class AutoSearchComplete {
    private static AutoSearchComplete instance;
    private static final Lock lock = new ReentrantLock();
    private RankingStrategy rankingStrategy;
    private Trie trie;
    private int maxSuggestions;

    private AutoSearchComplete(int maxSuggestions, RankingStrategy rankingStrategy) {
        trie = new Trie();
        this.maxSuggestions = maxSuggestions;
        this.rankingStrategy = rankingStrategy;
    }

    public static AutoSearchComplete getInstance(int maxSuggestions, RankingStrategy rankingStrategy) {
        if (instance == null) {
            lock.lock();
            try{
                if(instance == null){ //doubly checked
                    instance = new AutoSearchComplete(maxSuggestions, rankingStrategy);
                }
            } finally {
                lock.unlock();
            }
        }
        return  instance;
    }

    public void setRankingStrategy(RankingStrategy rankingStrategy) {
        this.rankingStrategy = rankingStrategy;
    }

    public void insertWords(List<String> words){
        for(String word: words){
            trie.insertWord(word);
        }
        System.out.println(words.size() + " words inserted successfully");
    }

    public List<String> getSuggestions(String prefix){
        List<Suggestions> rawSuggestions = trie.getSuggestions(prefix);
        List<Suggestions>filteredSuggestions = rankingStrategy.getSuggestions(rawSuggestions);
        List<String>filteredWords = filteredSuggestions.stream()
                .limit(maxSuggestions)
                .map(Suggestions::getWord)
                .collect(Collectors.toList());

        System.out.println("filtered words for prefix- [" + prefix + "] are: " + filteredWords);
        return filteredWords;
    }

    public boolean searchPrefix(String prefix){
       boolean ans = trie.containsWord(prefix);
        System.out.println("prefix: " + prefix + " isPrefixPresent: " + ans);
        return ans;
    }

    public static  void main(String[] args){
        RankingStrategy rankingStrategy = new FrequencyRankingStrategy();
        AutoSearchComplete autoSearchComplete = new AutoSearchComplete(2, rankingStrategy);
        autoSearchComplete.insertWords(Arrays.asList(new String[]{"ap",
                "app",
                "appl",
                "apple",
                "apple",
                "apples",
                "hu",
                "huma",
                "humans",
                "humans"}));

        autoSearchComplete.getSuggestions("a");
        autoSearchComplete.getSuggestions("appl");
        autoSearchComplete.getSuggestions("hum");
    }
}
