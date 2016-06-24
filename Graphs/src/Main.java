import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Created by artyom on 08.06.16.
 */
public class Main {
    public static void main(String[] args) {
        node1();
        node2();
    }

    static void node1() {
        System.out.println("Node1");

        Node1 first = new Node1();
        Node1 second = new Node1();
        Node1 trird = new Node1();

        Node1 main = new Node1();
        main.addNode(first);
        main.addNode(second);
        main.addNode(trird);

        assert main.getNode(1).equals(second);
        assert !main.getNode(0).equals(second);
        assert !main.getNode(2).equals(second);

        main.getParent();
    }

    static void node2() {
        System.out.println("Node2");

        Node2 first = new Node2();
        Node2 second = new Node2();
        Node2 third = new Node2();

        Node2 main = new Node2();
        main.addNode(first);
        main.addNode(second);
        main.addNode(third);

        assert main.getNodeList().get(1).equals(second);
        assert !main.getNodeList().get(0).equals(second);
        assert !main.getNodeList().get(2).equals(second);

        main.getParentNode();
    }
}

class Node<E> {
    private int value;
    private static int counter = 0;
    protected List<E> subnodes = new ArrayList<E>();
    protected E parent;

    Node() {
        this.value = ++counter;
    }

    void addNode(E node) {
        subnodes.add(node);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Node<?> node = (Node<?>) o;

        return value == node.value;
    }

    @Override
    public int hashCode() {
        return value;
    }
}

class Node1 extends Node<Node1> {
    Node1 getNode(int i) {
        return subnodes.get(i);
    }
    Node1 getParent() {
        return parent;
    }
}

class Node2 extends Node<Node2> {
    List<Node2> getNodeList() {
        return subnodes;
    }
    Node2 getParentNode() {
        return parent;
    }
}

class Node3 extends Node<Node3> {
    Iterator<Node3> getNodeIterator() {
        return subnodes.iterator();
    }
}