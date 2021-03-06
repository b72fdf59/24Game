package JPoker24GameServer;

import Common.JPokerInterface;
import Common.JPokerUser;
import Common.JPokerUserTransferObject;
import Common.Messages.GameOverMessage;
import Common.Messages.UserMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

import javax.naming.NamingException;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JPokerServer extends UnicastRemoteObject implements JPokerInterface {
    //    private static final String userInfoPath = "UserInfo.txt";
    private static final String onlineUserPath = "OnlineUser.txt";
    private final Connection conn;
    private final JMSHelperServer jmsHelper = new JMSHelperServer();
    private final ReadMessage messageReader = new ReadMessage();
    private final JPokerRoomManager roomManager = new JPokerRoomManager(this);

    protected JPokerServer() throws RemoteException, SQLException, NamingException, JMSException {

        super();
        String DB_USER = "c3358";
        String DB_PASS = "c3358PASS";
        String DB_HOST = "localhost";
        String DB_NAME = "c3358";
        conn = DriverManager.getConnection("jdbc:mysql://" + DB_HOST + "/" + DB_NAME, DB_USER, DB_PASS);
    }

    public static void main(String[] args) {
        try {
            JPokerServer server = new JPokerServer();
            System.setSecurityManager(new SecurityManager());
            Naming.rebind("Server.JPokerServer", server);
            setupOnlineUser();
            new Thread(server.messageReader).start();
        } catch (Exception e) {
            System.err.println("Remote Exception thrown: " + e);
            e.printStackTrace();
        }
    }

    private static void setupOnlineUser() {
        synchronized (onlineUserPath) {
            File onlineUserFile = new File(onlineUserPath);
            try {
                if (!onlineUserFile.createNewFile()) {
                    // File exists
                    PrintWriter writer = new PrintWriter(onlineUserFile);
                    writer.print("");
                    writer.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new Error(e);
            }
        }
    }

    private static boolean checkValidity(String string, Integer[] cards) {
        Set<Integer> numbers = new HashSet<>();

        Pattern p = Pattern.compile("\\d+");
        Matcher m = p.matcher(string);

        while (m.find()) {
            Integer i = Integer.parseInt(m.group());
            if (i < 1 || i > 13)
                return false;
            numbers.add(i);
        }

        if (numbers.size() != 4) {
            return false;
        }

        for (Integer number : numbers) {
            if (Arrays.stream(cards).noneMatch(number::equals)) {
                return false;
            }
        }

        return true;
    }

    public JMSHelperServer getJmsHelper() {
        return jmsHelper;
    }

    public ArrayList<String> readOnlineUser() {
        ArrayList<String> onlineUsers = null;
        synchronized (onlineUserPath) {
            try (ObjectInputStream onlineUserReader = new ObjectInputStream(new FileInputStream(onlineUserPath))) {
                {
                    onlineUsers = (ArrayList<String>) onlineUserReader.readObject();
                }
            } catch (Exception e) {
                e.printStackTrace();
//                throw new Error(e);
            }
        }
        return onlineUsers;
    }

    public void writeOnlineUser(String username) throws IOException {
        synchronized (onlineUserPath) {
            ArrayList<String> onlineUsers = readOnlineUser();

            if (onlineUsers == null) {
                onlineUsers = new ArrayList<>();
            }
            onlineUsers.add(username);

            try (ObjectOutputStream onlineUserWriter = new ObjectOutputStream(new FileOutputStream(onlineUserPath))) {
                onlineUserWriter.writeObject(onlineUsers);
            }
        }
    }

    public void writeOnlineUser(ArrayList<String> onlineUsers) throws IOException {
        synchronized (onlineUserPath) {
            try (ObjectOutputStream onlineUserWriter = new ObjectOutputStream(new FileOutputStream(onlineUserPath))) {
                onlineUserWriter.writeObject(onlineUsers);
            }
        }
    }

    private boolean isOnlineUserLoggedIn(ArrayList<String> users, String username) {
        boolean isLoggedIn = false;
        for (String onlineUser : users) {
            if (onlineUser.equals(username)) {
                isLoggedIn = true;
                break;
            }
        }
        return isLoggedIn;
    }

    private boolean validateUser(String loginName, String password) throws SQLException {
        boolean isValidated = false;
        PreparedStatement stmt = conn.prepareStatement("SELECT password FROM c3358.user WHERE username=?");
        stmt.setString(1, loginName);
        ResultSet rs = stmt.executeQuery();
        if (rs.next()) {
            if (rs.getString(1).equals(password))
                isValidated = true;
        }
        return isValidated;
    }

    private boolean checkRepeatLogin(String loginName) {
        ArrayList<String> onlineUsers = readOnlineUser();
        if (onlineUsers == null) {
            onlineUsers = new ArrayList<>();
        }
        return isOnlineUserLoggedIn(onlineUsers, loginName);
    }

    private JPokerUser[] listUserFromDatabase() {
        PreparedStatement stmt;
        ArrayList<JPokerUser> users = new ArrayList<>();
        try {
            String selectStatement = "SELECT user.username, user.password,user.games_played, " +
                    "user.win, COALESCE(win.avg_win_time,0) AS avg_win_time, " +
                    "ROW_NUMBER() OVER(ORDER BY win) AS ranking " +
                    "FROM user " +
                    "LEFT JOIN " +
                    "(SELECT username, AVG(win_time) AS avg_win_time " +
                    "FROM win_time " +
                    "GROUP BY username) AS win " +
                    "ON user.username = win.username " +
                    "ORDER BY win " +
                    "LIMIT 10";
            stmt = conn.prepareStatement(selectStatement);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString(1);
                String password = rs.getString(2);
                Integer gamesPlayed = rs.getInt(3);
                Integer wins = rs.getInt(4);
                Double averageTimeToWin = rs.getDouble(5)/1000;
                Integer rank = rs.getInt(6);
                users.add(new JPokerUser(name, password, gamesPlayed, wins, rank, averageTimeToWin));
            }
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
        return users.stream().limit(10).toArray(JPokerUser[]::new);
    }

    private JPokerUser getUserFromDatabase(String username) {
        PreparedStatement stmt;
        JPokerUser user = null;
        try {
            String selectStatement = "SELECT user.username, user.password,user.games_played, " +
                    "user.win, COALESCE(win.avg_win_time,0) AS avg_win_time, " +
                    "ranking " +
                    "FROM user " +
                    "LEFT JOIN " +
                    "(SELECT username, AVG(win_time) AS avg_win_time " +
                    "FROM win_time " +
                    "GROUP BY username " +
                    "HAVING username=?) AS win " +
                    "ON user.username = win.username " +
                    "LEFT JOIN " +
                    "(SELECT * " +
                    "FROM (SELECT username, ROW_NUMBER() OVER(ORDER BY win) AS ranking " +
                    "FROM user " +
                    ") AS tmp " +
                    "WHERE username=?) AS ranking " +
                    "ON user.username = ranking.username " +
                    "WHERE user.username=?";
            stmt = conn.prepareStatement(selectStatement);
            stmt.setString(1, username);
            stmt.setString(2, username);
            stmt.setString(3, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String name = rs.getString(1);
                String password = rs.getString(2);
                Integer gamesPlayed = rs.getInt(3);
                Integer wins = rs.getInt(4);
                user = new JPokerUser(name, password, gamesPlayed, wins, 0, 0.0);
            }
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
        return user;
    }

    private boolean insertUserInDatabase(String name, String password) {
        try {
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO c3358.user (username, password) VALUES (?, ?)");
            stmt.setString(1, name);
            stmt.setString(2, password);
            return stmt.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new Error("UserName is taken");
        }
    }

    @Override
    public boolean login(String loginName, String password) throws RemoteException {
        try {
            if (!validateUser(loginName, password))
                throw new Error("Could not Validate User");
            synchronized (onlineUserPath) {
                if (checkRepeatLogin(loginName))
                    throw new Error("Repeat Login");
                writeOnlineUser(loginName);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new Error(e);
        }
        return true;
    }

    @Override
    public boolean register(String name, String password) {
        return insertUserInDatabase(name, password);
    }


    @Override
    public boolean logout(String name) throws RemoteException {
        synchronized (onlineUserPath) {
            try {
                ArrayList<String> onlineUsers = readOnlineUser();
                if (!onlineUsers.remove(name)) {
                    throw new Error("User not online");
                }
                writeOnlineUser(onlineUsers);
                roomManager.removeFromRoom(name);
            } catch (Exception e) {
                e.printStackTrace();
                throw new Error(e);
            }
        }
        return true;
    }

    @Override
    public JPokerUserTransferObject getUser(String name) throws RemoteException {
        JPokerUser user = getUserFromDatabase(name);
        if (user != null) {
            return new JPokerUserTransferObject(user);
        } else {
            throw new Error("Unable to fetch user " + name + " from database");
        }
    }

    @Override
    public JPokerUserTransferObject[] getLeaderBoard() throws RemoteException {
        return Arrays.stream(listUserFromDatabase())
                .map(JPokerUserTransferObject::new)
                .toArray(JPokerUserTransferObject[]::new);
    }

    private String parseExpression(String expression) throws ScriptException {
        ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
        ScriptEngine scriptEngine = scriptEngineManager.getEngineByName("JavaScript");
        return scriptEngine.eval(expression).toString();
    }

    @Override
    public String getAnswer(JPokerUserTransferObject user, String answer) throws RemoteException, ScriptException, JMSException {
        JPokerRoom room = roomManager.findRoomByUsername(user.getName());
        Integer[] cards = room.getCardNumbers();
        String tmp = answer.replace("A", "1").replace("J", "11").replace("Q", "12").replace("K", "13");

        boolean isValid = checkValidity(tmp, cards);
        if (!isValid) {
            return "?";
        }

        String parsedAnswer = parseExpression(tmp);
        if (Integer.parseInt(parsedAnswer) < 0) {
            return "?";
        }
        if (Integer.parseInt(parsedAnswer) == 24) {
            roomManager.endGame(room);
            updateGameOverDatabase(room, user);
            updateWinningTimeDatabase(room, user);
            GameOverMessage gameOverMessage = new GameOverMessage(room.getRoomId(), room.getPlayers(), user, answer);
            Message message = jmsHelper.createMessage(gameOverMessage);
            jmsHelper.broadcastMessage(message);
        }
        return parsedAnswer;
    }

    private void updateWinningTimeDatabase(JPokerRoom room, JPokerUserTransferObject user) {
        PreparedStatement stmt;
        try {
            String updateStatement = "INSERT INTO win_time (username, win_time) " +
                    "VALUES (?,?)";
            stmt = conn.prepareStatement(updateStatement);
            stmt.setString(1, user.getName());
            stmt.setDouble(2, room.getTimeToWin());
            stmt.executeUpdate();
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
    }

    private void updateGameOverDatabase(JPokerRoom room, JPokerUserTransferObject winner) {
        PreparedStatement stmt;
        try {
            String selectStatement = "SELECT username, games_played, win " +
                    "FROM user " +
                    "WHERE username=?";
            String updateStatement = "UPDATE user " +
                    "SET games_played=?, win=? " +
                    "WHERE username=?";
            for (JPokerUserTransferObject user : room.getPlayers()) {
                String name = user.getName();
                stmt = conn.prepareStatement(selectStatement);
                stmt.setString(1, name);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String username = rs.getString(1);
                    Integer games = rs.getInt(2);
                    Integer win = rs.getInt(3);
                    if (username.equals(winner.getName()))
                        win++;
                    games++;
                    stmt = conn.prepareStatement(updateStatement);
                    stmt.setInt(1, games);
                    stmt.setInt(2, win);
                    stmt.setString(3, name);
                    System.out.println(stmt.toString());
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
    }

    class CheckPlayer implements Runnable {
        public JPokerUserTransferObject user;

        public CheckPlayer(JPokerUserTransferObject user) {
            this.user = user;
        }

        @Override
        public void run() {

            if (roomManager.isUserPlaying(user.getName())) {
                System.err.println("User " + user.getName()
                        + " is already playing reject join message.");
                return;
            }
            JPokerUser player = getUserFromDatabase(user.getName());
            try {
                roomManager.allocateRoom(player);
            } catch (JMSException e) {
                e.printStackTrace();
            }
        }
    }

    private class ReadMessage implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    UserMessage message = jmsHelper.receiveMessage(jmsHelper.getQueueReader());
                    if (message != null) {
                        new Thread(new CheckPlayer(message.getUser())).start();
                    }
                } catch (JMSException e) {
                    e.printStackTrace();
                }
            }
        }
    }

//	Use this login function when not working with a database
//	@Override
//	public boolean register(String name, String password) throws RemoteException {
//		ArrayList<JPokerUser> users = null;
//		JPokerUser newUser = null;
//		synchronized (userInfoPath) {
//			try {
//				users = readUserInfo();
//				int userCount = 0;
//				if(users != null)
//					userCount = users.size();
//				if (users != null && existsSameLoginNameUser(users, name)) {
//					throw new Error("UserName is taken");
//				}
//				newUser = new JPokerUser(name, password, userCount);
//				writeUserInfo(newUser);
//			} catch (Exception e) {
//				System.err.print(e);
//				e.printStackTrace();
//			}
//		}
//		return (newUser != null);
//	}
//	private boolean validateUser(String loginName, String password) throws ClassNotFoundException {
//		boolean isValidated = false;
//		ArrayList<JPokerUser> users = readUserInfo();
//		JPokerUser user = getUserWithName(users, loginName);
//		if (user != null && user.verifyPassword(password)) {
//			isValidated = true;
//		}
//		return isValidated;
//	}
    //    private JPokerUser getUserWithName(ArrayList<JPokerUser> users, String username) {
//        JPokerUser user = null;
//        for (JPokerUser registeredUser : users) {
//            if (registeredUser.getName().equals(username)) {
//                user = registeredUser;
//            }
//        }
//        return user;
//    }
//    private boolean existsSameLoginNameUser(ArrayList<JPokerUser> users, String username) {
//        return (getUserWithName(users, username) != null);
//    }
//    public ArrayList<JPokerUser> readUserInfo() {
//        ArrayList<JPokerUser> users;
//        synchronized (userInfoPath) {
//            try (ObjectInputStream userInfoReader = new ObjectInputStream(new FileInputStream(userInfoPath))) {
//                {
//                    users = (ArrayList<JPokerUser>) userInfoReader.readObject();
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//                throw new Error(e);
//            }
//        }
//
//        return users;
//    }
//    public void writeUserInfo(JPokerUser user) throws IOException, ClassNotFoundException {
//        synchronized (userInfoPath) {
//            ArrayList<JPokerUser> users = readUserInfo();
//
//            if (users == null) {
//                users = new ArrayList<>();
//            }
//            users.add(user);
//
//            try (ObjectOutputStream userInfoWriter = new ObjectOutputStream(new FileOutputStream(userInfoPath))) {
//                userInfoWriter.writeObject(users);
//            }
//        }
//    }

}
